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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import org.midnightbsd.leonardo.storage.AtomicFileWriter;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;
import org.midnightbsd.leonardo.yaml.YamlMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes per-object YAML metadata files under
 * {@code <base_dir>/buckets/<bucket>/_meta/objects/<sha256-of-key>.yaml}.
 */
@Singleton
public final class ObjectMetadataStore {

    private static final Logger LOG = LoggerFactory.getLogger(ObjectMetadataStore.class);
    private static final int DEFAULT_MAX_KEYS = 1000;

    private final StorageLayout layout;
    private final AtomicFileWriter writer;
    private final ObjectMapper yaml = YamlMappers.get();

    public ObjectMetadataStore(
            final StorageLayout layout,
            final AtomicFileWriter writer) {
        this.layout = layout;
        this.writer = writer;
    }

    /** Writes (or overwrites) the object metadata YAML atomically. */
    public void write(final String bucket, final ObjectMetadata meta) throws IOException {
        final Path target = layout.objectMetaFile(bucket, meta.key());
        writer.write(target, out -> {
            try {
                yaml.writeValue(out, meta);
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    /** Reads object metadata. Returns empty if the object doesn't exist. */
    public Optional<ObjectMetadata> read(final String bucket, final String key) {
        final Path path = layout.objectMetaFile(bucket, key);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(yaml.readValue(path.toFile(), ObjectMetadata.class));
        } catch (final IOException ex) {
            LOG.warn("Failed to read object metadata for '{}/{}': {}", bucket, key, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reads a specific version of an object. Returns the current metadata if the
     * versionId matches, or reconstructs a partial ObjectMetadata from the version
     * history. Returns empty when no match is found.
     */
    public Optional<ObjectMetadata> readVersion(
            final String bucket,
            final String key,
            final String versionId) {
        final Optional<ObjectMetadata> current = read(bucket, key);
        if (current.isEmpty()) return Optional.empty();
        final ObjectMetadata meta = current.get();

        // Check if the request is for the current version
        if (versionId == null || versionId.equals(meta.versionId())) {
            return current;
        }

        // Search historical versions
        if (meta.versions() == null) return Optional.empty();
        for (final ObjectMetadata.ObjectVersion v : meta.versions()) {
            if (versionId.equals(v.versionId())) {
                final ObjectMetadata versioned = new ObjectMetadata(
                        meta.key(), v.objectId(), v.size(), meta.contentType(),
                        v.etag(), v.createdAt(), v.createdAt(), meta.storageClass(),
                        null, meta.userMetadata(), meta.tags(), meta.aclCanned(),
                        meta.legalHold(), meta.retention(), v.versionId(),
                        meta.checksums(), meta.partsCount());
                return Optional.of(versioned);
            }
        }
        return Optional.empty();
    }

    /** Deletes the object metadata file. Silently succeeds if it doesn't exist. */
    public void delete(final String bucket, final String key) throws IOException {
        Files.deleteIfExists(layout.objectMetaFile(bucket, key));
    }

    /** Returns {@code true} if the metadata file exists. */
    public boolean exists(final String bucket, final String key) {
        return Files.exists(layout.objectMetaFile(bucket, key));
    }

    /**
     * Lists objects in the bucket, applying prefix filtering, delimiter grouping,
     * and pagination (marker or continuation-token based).
     *
     * <p>Scanning is O(N) on the number of objects — acceptable for M2; an
     * in-memory index is planned for M6.
     */
    public ListPage list(
            final String bucket,
            final String prefix,
            final String delimiter,
            final int maxKeys,
            final String startAfterKey) throws IOException {

        final Path objectsDir = layout.bucketObjectsMetaDir(bucket);
        final var all = new ArrayList<ObjectMetadata>();

        if (Files.exists(objectsDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(objectsDir, "*.yaml")) {
                for (final Path p : ds) {
                    try {
                        final ObjectMetadata meta = yaml.readValue(p.toFile(), ObjectMetadata.class);
                        all.add(meta);
                    } catch (final IOException ex) {
                        LOG.warn("Skipping unreadable object metadata '{}': {}", p, ex.getMessage());
                    }
                }
            }
        }

        // Sort lexicographically by key (S3 listing order)
        all.sort(Comparator.comparing(ObjectMetadata::key));

        final int limit = maxKeys > 0 ? Math.min(maxKeys, DEFAULT_MAX_KEYS) : DEFAULT_MAX_KEYS;
        final var objects = new ArrayList<ObjectMetadata>();
        final var commonPrefixes = new java.util.TreeSet<String>();
        boolean truncated = false;
        String nextKey = null;

        for (final ObjectMetadata meta : all) {
            final String key = meta.key();

            // Skip keys that are not past the start-after marker
            if (startAfterKey != null && !startAfterKey.isEmpty()
                    && key.compareTo(startAfterKey) <= 0) {
                continue;
            }

            // Apply prefix filter
            if (prefix != null && !prefix.isEmpty() && !key.startsWith(prefix)) {
                continue;
            }

            // Apply delimiter grouping
            if (delimiter != null && !delimiter.isEmpty()) {
                final String afterPrefix = prefix != null ? key.substring(prefix.length()) : key;
                final int delimIdx = afterPrefix.indexOf(delimiter);
                if (delimIdx >= 0) {
                    final String cp = (prefix != null ? prefix : "")
                            + afterPrefix.substring(0, delimIdx + delimiter.length());
                    commonPrefixes.add(cp);
                    continue;
                }
            }

            if (objects.size() >= limit) {
                truncated = true;
                nextKey = key;
                break;
            }
            objects.add(meta);
        }

        return new ListPage(objects, new ArrayList<>(commonPrefixes), truncated, nextKey);
    }

    /** Result of a paginated object listing. */
    public record ListPage(
            List<ObjectMetadata> objects,
            List<String> commonPrefixes,
            boolean truncated,
            String nextKey) {}

    public record VersionEntry(
            String key,
            String versionId,
            String etag,
            long size,
            boolean deleteMarker,
            boolean isLatest,
            Instant lastModified,
            String storageClass) {}

    public record ListVersionsPage(
            List<VersionEntry> versions,
            List<String> commonPrefixes,
            boolean truncated,
            String nextKeyMarker,
            String nextVersionIdMarker) {}

    /**
     * Lists all versions of all objects in a bucket.  The current metadata is
     * the "latest" version; historical entries in {@link ObjectMetadata#versions()}
     * are emitted as older versions.
     */
    public ListVersionsPage listVersions(
            final String bucket,
            final String prefix,
            final String delimiter,
            final int maxKeys,
            final String keyMarker,
            final String versionIdMarker) throws IOException {

        final Path objectsDir = layout.bucketObjectsMetaDir(bucket);
        final var all = new ArrayList<ObjectMetadata>();

        if (Files.exists(objectsDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(objectsDir, "*.yaml")) {
                for (final Path p : ds) {
                    try {
                        all.add(yaml.readValue(p.toFile(), ObjectMetadata.class));
                    } catch (final IOException ex) {
                        LOG.warn("Skipping unreadable object metadata '{}': {}", p, ex.getMessage());
                    }
                }
            }
        }

        all.sort(Comparator.comparing(ObjectMetadata::key));

        final int limit = maxKeys > 0 ? Math.min(maxKeys, DEFAULT_MAX_KEYS) : DEFAULT_MAX_KEYS;
        final var versions = new ArrayList<VersionEntry>();
        final var commonPrefixes = new java.util.TreeSet<String>();
        boolean truncated = false;
        String nextKeyMarkerResult = null;
        String nextVersionIdResult = null;

        outer:
        for (final ObjectMetadata meta : all) {
            final String key = meta.key();

            if (keyMarker != null && !keyMarker.isEmpty() && key.compareTo(keyMarker) <= 0) {
                continue;
            }
            if (prefix != null && !prefix.isEmpty() && !key.startsWith(prefix)) {
                continue;
            }

            if (delimiter != null && !delimiter.isEmpty()) {
                final String afterPrefix = prefix != null ? key.substring(prefix.length()) : key;
                final int delimIdx = afterPrefix.indexOf(delimiter);
                if (delimIdx >= 0) {
                    commonPrefixes.add((prefix != null ? prefix : "")
                            + afterPrefix.substring(0, delimIdx + delimiter.length()));
                    continue;
                }
            }

            // Current (latest) version (or delete marker)
            if (versions.size() >= limit) {
                truncated = true;
                nextKeyMarkerResult = key;
                break;
            }
            final boolean isMarker = meta.objectId() == null || meta.objectId().isEmpty();
            versions.add(new VersionEntry(key, meta.versionId(),
                    isMarker ? "" : meta.etag(),
                    isMarker ? 0 : meta.size(),
                    isMarker, true, meta.lastModified(),
                    meta.storageClass() != null ? meta.storageClass() : "STANDARD"));

            // Historical versions (oldest first within the same key)
            if (meta.versions() != null) {
                final var hist = new ArrayList<>(meta.versions());
                hist.sort(Comparator.comparing(ObjectMetadata.ObjectVersion::createdAt).reversed());
                for (final ObjectMetadata.ObjectVersion v : hist) {
                    if (versions.size() >= limit) {
                        truncated = true;
                        nextKeyMarkerResult = key;
                        nextVersionIdResult = v.versionId();
                        break outer;
                    }
                    versions.add(new VersionEntry(key, v.versionId(), v.etag(), v.size(),
                            v.deleteMarker(), false, v.createdAt(), "STANDARD"));
                }
            }
        }

        return new ListVersionsPage(versions, new ArrayList<>(commonPrefixes),
                truncated, nextKeyMarkerResult, nextVersionIdResult);
    }
}
