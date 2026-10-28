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
    private final StripedLockManager locks = new StripedLockManager();

    public ObjectPublisher(final ObjectMetadataStore metaStore, final BucketMetadataStore bucketStore) {
        this.metaStore = metaStore;
        this.bucketStore = bucketStore;
    }

    /** Per-{@code bucket/key} locks shared by every service that rewrites object metadata. */
    public StripedLockManager locks() {
        return locks;
    }

    /**
     * Writes {@code draft} as the current version of its key. The draft's
     * {@code versions} and {@code versionId} are ignored: when the bucket has
     * versioning enabled a fresh version id is minted and the previous current
     * record (object or delete marker) is pushed onto the history; otherwise
     * the record is written unversioned.
     *
     * @return the new version id, or {@code null} when versioning is not enabled
     * @throws S3Exception when the bucket is missing, immutable, or the existing
     *                     object is under COMPLIANCE retention
     */
    public String publish(final String bucket, final ObjectMetadata draft) throws IOException {
        final BucketMetadata bucketMeta = bucketStore.read(bucket)
                .orElseThrow(() -> S3Exception.noSuchBucket(bucket));
        final boolean versioningEnabled = bucketMeta.versioning() != null
                && bucketMeta.versioning().status() == BucketMetadata.VersioningStatus.ENABLED;

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
            if (versioningEnabled) {
                newVersionId = Ulid.generate();
                if (existing.isPresent()) {
                    history = historyWith(existing.get());
                }
            }

            final var meta = new ObjectMetadata(
                    draft.key(), draft.objectId(), draft.size(), draft.contentType(), draft.etag(),
                    draft.createdAt(), draft.lastModified(), draft.storageClass(),
                    history, draft.userMetadata(), draft.tags(), draft.aclCanned(),
                    draft.legalHold(), draft.retention(), newVersionId,
                    draft.checksums(), draft.partsCount(), draft.contentHeaders());
            metaStore.write(bucket, meta);
            return newVersionId;
        } finally {
            lock.writeLock().unlock();
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
