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
package org.midnightbsd.leonardo.core.multipart;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.midnightbsd.leonardo.core.S3Exception;
import org.midnightbsd.leonardo.core.Ulid;
import org.midnightbsd.leonardo.core.bucket.BucketMetadataStore;
import org.midnightbsd.leonardo.core.object.ObjectMetadata;
import org.midnightbsd.leonardo.core.object.ObjectMetadataStore;
import org.midnightbsd.leonardo.core.object.ObjectPublisher;
import org.midnightbsd.leonardo.core.object.ObjectService;
import org.midnightbsd.leonardo.storage.BoundedInputStream;
import org.midnightbsd.leonardo.storage.ObjectPayloadStore;
import org.midnightbsd.leonardo.storage.PartStore;
import org.midnightbsd.leonardo.storage.lock.StripedLockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Business logic for multipart upload operations: CreateMultipartUpload,
 * UploadPart, UploadPartCopy, CompleteMultipartUpload, AbortMultipartUpload,
 * ListMultipartUploads, ListParts.
 *
 * <p>Upload-state mutations are serialised per {@code uploadId} via the
 * {@link StripedLockManager}. The final object write during
 * {@code CompleteMultipartUpload} acquires the per-{@code bucket/key} stripe
 * lock (shared with {@link org.midnightbsd.leonardo.core.object.ObjectService})
 * to prevent races with concurrent {@code PutObject} calls.
 */
@Singleton
public final class MultipartService {

    private static final Logger LOG = LoggerFactory.getLogger(MultipartService.class);
    private static final int MAX_PART_NUMBER = 10_000;

    private final MultipartUploadStore uploadStore;
    private final PartStore partStore;
    private final ObjectPayloadStore payloadStore;
    private final ObjectMetadataStore metaStore;
    private final BucketMetadataStore bucketStore;
    private final ObjectPublisher publisher;
    private final StripedLockManager locks;
    private final long minPartSize;

    public MultipartService(
            final MultipartUploadStore uploadStore,
            final PartStore partStore,
            final ObjectPayloadStore payloadStore,
            final ObjectMetadataStore metaStore,
            final BucketMetadataStore bucketStore,
            final ObjectPublisher publisher,
            @Value("${leonardo.limits.min_part_size:5242880}") final long minPartSize) {
        this.uploadStore  = uploadStore;
        this.partStore    = partStore;
        this.payloadStore = payloadStore;
        this.metaStore    = metaStore;
        this.bucketStore  = bucketStore;
        this.publisher    = publisher;
        this.locks        = publisher.locks();
        this.minPartSize  = minPartSize;
    }

    // -------------------------------------------------------------------------
    // CreateMultipartUpload
    // -------------------------------------------------------------------------

    /**
     * Initiates a multipart upload. Returns the new {@code uploadId}.
     */
    public String createMultipartUpload(
            final String bucket,
            final String key,
            final String owner,
            final String contentType,
            final Map<String, String> userMetadata,
            final String callerObjectId) throws IOException {
        return createMultipartUpload(bucket, key, owner, contentType, userMetadata,
                null, null, callerObjectId);
    }

    /**
     * Starts a multipart upload, recording the tags and entity headers that
     * CompleteMultipartUpload will put on the finished object.
     */
    public String createMultipartUpload(
            final String bucket,
            final String key,
            final String owner,
            final String contentType,
            final Map<String, String> userMetadata,
            final Map<String, String> tags,
            final ObjectMetadata.ContentHeaders contentHeaders,
            final String callerObjectId) throws IOException {
        ensureBucketExists(bucket);

        final String uploadId = Ulid.generate();
        final String objectId = Ulid.generate();

        final var upload = new MultipartUpload(
                uploadId, bucket, key, objectId, owner,
                contentType != null && !contentType.isEmpty()
                        ? contentType : "application/octet-stream",
                Instant.now(), userMetadata,
                callerObjectId != null && !callerObjectId.isEmpty() ? callerObjectId : null,
                List.of(),
                tags != null && !tags.isEmpty() ? tags : null,
                contentHeaders);

        uploadStore.write(upload);
        return uploadId;
    }

    // -------------------------------------------------------------------------
    // UploadPart
    // -------------------------------------------------------------------------

    /**
     * Stores {@code data} as part {@code partNumber} of the given upload.
     * Returns the part ETag (MD5 hex, without quotes).
     */
    public String uploadPart(
            final String bucket,
            final String key,
            final String uploadId,
            final int partNumber,
            final byte[] data) throws IOException {
        validatePartNumber(partNumber);
        final MultipartUpload upload = requireUpload(bucket, uploadId);
        validateUploadKey(upload, key);

        final byte[] payload = data != null ? data : new byte[0];
        final String etag = partStore.write(uploadId, partNumber, payload);

        updateUploadWithPart(bucket, uploadId, new MultipartUpload.PartInfo(
                partNumber, etag, payload.length, Instant.now()));

        return etag;
    }

    /** Streams a multipart part to storage so its size does not consume heap. */
    public String uploadPart(
            final String bucket, final String key, final String uploadId,
            final int partNumber, final InputStream data) throws IOException {
        validatePartNumber(partNumber);
        final MultipartUpload upload = requireUpload(bucket, uploadId);
        validateUploadKey(upload, key);
        final ObjectPayloadStore.WriteResult stored = partStore.write(uploadId, partNumber, data);
        updateUploadWithPart(bucket, uploadId, new MultipartUpload.PartInfo(
                partNumber, stored.etag(), stored.size(), Instant.now()));
        return stored.etag();
    }

    // -------------------------------------------------------------------------
    // UploadPartCopy
    // -------------------------------------------------------------------------

    /**
     * Copies a byte range (or the full object) from {@code srcBucket/srcKey} as a
     * part of the given upload. Returns the part ETag.
     *
     * @param rangeStart inclusive start offset (null → copy entire object)
     * @param rangeEnd   inclusive end offset (null → copy entire object)
     */
    public String uploadPartCopy(
            final String bucket,
            final String key,
            final String uploadId,
            final int partNumber,
            final String srcBucket,
            final String srcKey,
            final Long rangeStart,
            final Long rangeEnd) throws IOException {
        return uploadPartCopy(bucket, key, uploadId, partNumber, srcBucket, srcKey, null,
                rangeStart, rangeEnd).etag();
    }

    /** Result of UploadPartCopy. */
    public record PartCopyResult(String etag, long size, String sourceVersionId) {}

    /**
     * Streams a byte range (or the whole object) of {@code srcBucket/srcKey},
     * optionally a specific version of it, into a part of the given upload.
     *
     * @throws S3Exception InvalidArgument when the range does not fit the source
     */
    public PartCopyResult uploadPartCopy(
            final String bucket,
            final String key,
            final String uploadId,
            final int partNumber,
            final String srcBucket,
            final String srcKey,
            final String srcVersionId,
            final Long rangeStart,
            final Long rangeEnd) throws IOException {
        validatePartNumber(partNumber);
        ensureBucketExists(srcBucket);
        final MultipartUpload upload = requireUpload(bucket, uploadId);
        validateUploadKey(upload, key);

        final boolean specificVersion = srcVersionId != null && !srcVersionId.isEmpty();
        final ObjectMetadata srcMeta = (specificVersion
                ? metaStore.readVersion(srcBucket, srcKey, srcVersionId)
                : metaStore.read(srcBucket, srcKey))
                .orElseThrow(() -> ObjectService.versionNotFound(metaStore, srcBucket, srcKey, srcVersionId));
        if (srcMeta.isDeleteMarker()) {
            throw S3Exception.noSuchKey(srcKey);
        }

        final long size = srcMeta.size();
        long start = 0;
        long end = size - 1;
        if (rangeStart != null || rangeEnd != null) {
            // S3 rejects any range that does not lie entirely inside the source,
            // including an end past the last byte; it never clamps.
            if (rangeStart == null || rangeEnd == null || rangeStart < 0 || rangeEnd < rangeStart
                    || rangeEnd >= size) {
                throw new S3Exception(S3Exception.INVALID_ARGUMENT,
                        "Range specified is not valid for source object of size: " + size, 400);
            }
            start = rangeStart;
            end = rangeEnd;
        }

        final ObjectPayloadStore.WriteResult stored;
        try (InputStream in = payloadStore.open(srcBucket, srcMeta.objectId())) {
            in.skipNBytes(start);
            stored = partStore.write(uploadId, partNumber,
                    new BoundedInputStream(in, size == 0 ? 0 : end - start + 1));
        }
        updateUploadWithPart(bucket, uploadId, new MultipartUpload.PartInfo(
                partNumber, stored.etag(), stored.size(), Instant.now()));
        return new PartCopyResult(stored.etag(), stored.size(),
                srcMeta.versionId() != null ? srcMeta.versionId() : (specificVersion ? "null" : null));
    }

    // -------------------------------------------------------------------------
    // CompleteMultipartUpload
    // -------------------------------------------------------------------------

    /**
     * A (partNumber, etag) reference provided in a {@code CompleteMultipartUpload} request.
     * The controller maps the parsed XML {@code <Part>} elements to this type before
     * calling into the service.
     */
    public record PartRef(int partNumber, String etag) {}

    /** Result of CompleteMultipartUpload; {@code versionId} is null unless bucket versioning is enabled. */
    public record CompleteResult(String etag, long size, String versionId) {}

    /**
     * Validates the provided parts list, assembles the final object payload by
     * streaming part files, writes object metadata, and cleans up upload state.
     *
     * <p>Every validation runs under the upload lock before the upload YAML is
     * deleted, so a rejected request (unknown part, ETag mismatch, undersized
     * part) leaves the upload intact and the client can fix the request, retry
     * or abort. Once the parts check out the YAML is deleted while still
     * holding the lock, so a concurrent {@code CompleteMultipartUpload} for
     * the same uploadId receives {@code NoSuchUpload}.
     */
    public CompleteResult completeMultipartUpload(
            final String bucket,
            final String key,
            final String uploadId,
            final List<PartRef> clientParts) throws IOException {

        final MultipartUpload upload;
        final List<PartRef> ordered;
        final var partPaths = new ArrayList<java.nio.file.Path>();
        final var partEtags = new ArrayList<String>();
        final var uploadLock = locks.lockFor("mp:" + uploadId);
        uploadLock.writeLock().lock();
        try {
            upload = requireUpload(bucket, uploadId);
            validateUploadKey(upload, key);
            ordered = orderedParts(clientParts);

            final Map<Integer, MultipartUpload.PartInfo> stored = new java.util.HashMap<>();
            if (upload.parts() != null) {
                upload.parts().forEach(pi -> stored.put(pi.partNumber(), pi));
            }
            for (int i = 0; i < ordered.size(); i++) {
                final PartRef part = ordered.get(i);
                final MultipartUpload.PartInfo info = stored.get(part.partNumber());
                final java.nio.file.Path p = partStore.partPath(uploadId, part.partNumber());
                if (info == null || !java.nio.file.Files.exists(p)
                        || !etagMatches(part.etag(), info.etag())) {
                    throw invalidPart(part.partNumber());
                }
                if (i < ordered.size() - 1 && info.size() < minPartSize) {
                    throw new S3Exception("EntityTooSmall",
                            "Your proposed upload is smaller than the minimum allowed object size. "
                            + "Each part must be at least " + minPartSize
                            + " bytes in size, except the last part.", 400);
                }
                partPaths.add(p);
                partEtags.add(info.etag());
            }

            // Claim the upload: from here on it no longer exists for other callers.
            uploadStore.delete(bucket, uploadId);
        } finally {
            uploadLock.writeLock().unlock();
        }

        // Step 3: assemble payload
        final String objectId = upload.objectId();
        final long totalSize  = payloadStore.writeFromParts(bucket, objectId, partPaths);
        final String etag     = ObjectPayloadStore.computeMultipartEtag(partEtags);

        // Step 4: publish object metadata through the same path as PutObject so
        // versioning, immutability and retention apply to multipart objects too
        final Instant now = Instant.now();
        final var draft = new ObjectMetadata(
                key, objectId, totalSize, upload.contentType(), etag,
                now, now, "STANDARD",
                null, upload.userMetadata(), upload.tags(), "private", false, null,
                null, null, ordered.size(), upload.contentHeaders());
        final String versionId = publisher.publish(bucket, draft);

        // Step 5: clean up parts (best effort)
        try {
            partStore.deleteUpload(uploadId);
        } catch (final IOException ex) {
            LOG.warn("Failed to delete parts for upload '{}': {}", uploadId, ex.getMessage());
        }

        return new CompleteResult(etag, totalSize, versionId);
    }

    // -------------------------------------------------------------------------
    // AbortMultipartUpload
    // -------------------------------------------------------------------------

    /**
     * Aborts the upload, deleting the state YAML and all part files.
     *
     * @throws S3Exception with code {@code NoSuchUpload} if the upload does not exist
     */
    public void abortMultipartUpload(
            final String bucket,
            final String key,
            final String uploadId) throws IOException {
        final var lock = locks.lockFor("mp:" + uploadId);
        lock.writeLock().lock();
        try {
            requireUpload(bucket, uploadId);
            uploadStore.delete(bucket, uploadId);
        } finally {
            lock.writeLock().unlock();
        }

        try {
            partStore.deleteUpload(uploadId);
        } catch (final IOException ex) {
            LOG.warn("Failed to delete parts for aborted upload '{}': {}", uploadId, ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // ListMultipartUploads
    // -------------------------------------------------------------------------

    public record ListUploadsPage(
            List<MultipartUpload> uploads,
            List<String> commonPrefixes,
            boolean truncated,
            String nextKeyMarker,
            String nextUploadIdMarker) {}

    /**
     * Lists in-progress uploads for {@code bucket}, applying prefix filtering,
     * delimiter grouping, and key-marker pagination.
     */
    public ListUploadsPage listMultipartUploads(
            final String bucket,
            final String prefix,
            final String delimiter,
            final String keyMarker,
            final String uploadIdMarker,
            final int maxUploads) throws IOException {
        ensureBucketExists(bucket);

        final List<MultipartUpload> all = uploadStore.listAll(bucket);
        all.sort(Comparator.comparing(MultipartUpload::key)
                .thenComparing(MultipartUpload::uploadId));

        final int limit = maxUploads > 0 ? Math.min(maxUploads, 1000) : 1000;
        final var result      = new ArrayList<MultipartUpload>();
        final var commonPfxs  = new TreeSet<String>();
        boolean truncated     = false;
        String nextKeyMkr     = null;
        String nextUploadMkr  = null;

        for (final MultipartUpload u : all) {
            if (keyMarker != null && !keyMarker.isEmpty()) {
                final int cmp = u.key().compareTo(keyMarker);
                if (cmp < 0) continue;
                if (cmp == 0) {
                    if (uploadIdMarker != null && !uploadIdMarker.isEmpty()
                            && u.uploadId().compareTo(uploadIdMarker) <= 0) continue;
                }
            }
            if (prefix != null && !prefix.isEmpty() && !u.key().startsWith(prefix)) continue;

            if (delimiter != null && !delimiter.isEmpty()) {
                final String afterPrefix = prefix != null ? u.key().substring(prefix.length()) : u.key();
                final int idx = afterPrefix.indexOf(delimiter);
                if (idx >= 0) {
                    commonPfxs.add((prefix != null ? prefix : "") + afterPrefix.substring(0, idx + delimiter.length()));
                    continue;
                }
            }

            if (result.size() >= limit) {
                truncated = true;
                nextKeyMkr    = u.key();
                nextUploadMkr = u.uploadId();
                break;
            }
            result.add(u);
        }

        return new ListUploadsPage(result, new ArrayList<>(commonPfxs),
                truncated, nextKeyMkr, nextUploadMkr);
    }

    // -------------------------------------------------------------------------
    // ListParts
    // -------------------------------------------------------------------------

    public record ListPartsPage(
            List<MultipartUpload.PartInfo> parts,
            boolean truncated,
            int nextPartNumberMarker) {}

    /**
     * Lists uploaded parts for the given upload, with pagination via
     * {@code partNumberMarker} and {@code maxParts}.
     */
    public ListPartsPage listParts(
            final String bucket,
            final String key,
            final String uploadId,
            final int partNumberMarker,
            final int maxParts) {
        final MultipartUpload upload = requireUpload(bucket, uploadId);
        validateUploadKey(upload, key);

        final int limit = maxParts > 0 ? Math.min(maxParts, 1000) : 1000;
        final List<MultipartUpload.PartInfo> sorted = upload.parts() == null
                ? List.of()
                : upload.parts().stream()
                        .filter(p -> p.partNumber() > partNumberMarker)
                        .sorted(Comparator.comparingInt(MultipartUpload.PartInfo::partNumber))
                        .toList();

        if (sorted.size() <= limit) {
            return new ListPartsPage(sorted, false, 0);
        }
        final var page = sorted.subList(0, limit);
        return new ListPartsPage(page, true, page.get(page.size() - 1).partNumber());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns the upload state, or throws {@code NoSuchUpload} if not found. */
    public MultipartUpload getUpload(final String bucket, final String uploadId) {
        return requireUpload(bucket, uploadId);
    }

    /** Sorts the client's part list and rejects empty lists, bad numbers and duplicates. */
    private static List<PartRef> orderedParts(final List<PartRef> clientParts) {
        if (clientParts == null || clientParts.isEmpty()) {
            throw new S3Exception(S3Exception.INVALID_ARGUMENT,
                    "You must specify at least one part.", 400);
        }
        final List<PartRef> ordered = clientParts.stream()
                .sorted(Comparator.comparingInt(PartRef::partNumber))
                .toList();
        final int first = ordered.get(0).partNumber();
        final int last  = ordered.get(ordered.size() - 1).partNumber();
        if (first < 1 || last > MAX_PART_NUMBER) {
            throw new S3Exception(S3Exception.INVALID_ARGUMENT,
                    "Part numbers must be between 1 and " + MAX_PART_NUMBER, 400);
        }
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).partNumber() == ordered.get(i - 1).partNumber()) {
                throw new S3Exception(S3Exception.INVALID_ARGUMENT,
                        "Duplicate part number: " + ordered.get(i).partNumber(), 400);
            }
        }
        return ordered;
    }

    /** Compares a client-supplied part ETag (quotes optional) with the stored MD5 hex. */
    static boolean etagMatches(final String clientEtag, final String storedEtag) {
        if (clientEtag == null || storedEtag == null) return false;
        String c = clientEtag.trim();
        if (c.length() >= 2 && c.startsWith("\"") && c.endsWith("\"")) {
            c = c.substring(1, c.length() - 1);
        }
        String s = storedEtag.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return !c.isEmpty() && c.equalsIgnoreCase(s);
    }

    private static S3Exception invalidPart(final int partNumber) {
        return new S3Exception("InvalidPart",
                "One or more of the specified parts could not be found. The part may not have "
                + "been uploaded, or the specified entity tag may not match the part's entity tag. "
                + "(part " + partNumber + ")", 400);
    }

    private void ensureBucketExists(final String bucket) {
        if (!bucketStore.exists(bucket)) {
            throw S3Exception.noSuchBucket(bucket);
        }
    }

    /**
     * Shape of every upload ID this server mints ({@link Ulid#generate()}:
     * 26 Crockford base-32 characters). Anything else is rejected before it
     * can reach the filesystem.
     */
    static final Pattern UPLOAD_ID_PATTERN = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    static boolean isWellFormedUploadId(final String uploadId) {
        return uploadId != null && UPLOAD_ID_PATTERN.matcher(uploadId).matches();
    }

    private MultipartUpload requireUpload(final String bucket, final String uploadId) {
        if (!isWellFormedUploadId(uploadId)) {
            throw noSuchUpload();
        }
        return uploadStore.read(bucket, uploadId).orElseThrow(MultipartService::noSuchUpload);
    }

    private static S3Exception noSuchUpload() {
        return new S3Exception("NoSuchUpload",
                "The specified upload does not exist. The upload ID may be invalid, "
                + "or the upload may have been aborted or completed.", 404);
    }

    private static void validateUploadKey(final MultipartUpload upload, final String key) {
        if (!upload.key().equals(key)) {
            throw new S3Exception("NoSuchUpload",
                    "The specified upload does not exist for the given key.", 404);
        }
    }

    private static void validatePartNumber(final int partNumber) {
        if (partNumber < 1 || partNumber > MAX_PART_NUMBER) {
            throw new S3Exception(S3Exception.INVALID_ARGUMENT,
                    "Part number must be between 1 and " + MAX_PART_NUMBER + ", got: " + partNumber,
                    400);
        }
    }

    /** Replaces or inserts {@code newPart} in the upload state under the upload lock. */
    private void updateUploadWithPart(
            final String bucket,
            final String uploadId,
            final MultipartUpload.PartInfo newPart) throws IOException {
        final var lock = locks.lockFor("mp:" + uploadId);
        lock.writeLock().lock();
        try {
            final MultipartUpload current = requireUpload(bucket, uploadId);
            final var parts = new ArrayList<>(
                    current.parts() != null ? current.parts() : List.of());
            parts.removeIf(p -> p.partNumber() == newPart.partNumber());
            parts.add(newPart);
            parts.sort(Comparator.comparingInt(MultipartUpload.PartInfo::partNumber));
            uploadStore.write(new MultipartUpload(
                    current.uploadId(), current.bucket(), current.key(), current.objectId(),
                    current.owner(), current.contentType(), current.initiated(),
                    current.userMetadata(), current.callerObjectId(), parts,
                    current.tags(), current.contentHeaders()));
        } finally {
            lock.writeLock().unlock();
        }
    }
}
