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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes {@code bucket.yaml} files using atomic rename.
 *
 * <p>All methods are thread-safe: serialization is handled by the stateless
 * Jackson mapper; callers in {@link BucketService} acquire the appropriate
 * stripe lock before calling write operations.
 */
@Singleton
public final class BucketMetadataStore {

    private static final Logger LOG = LoggerFactory.getLogger(BucketMetadataStore.class);

    private final StorageLayout layout;
    private final AtomicFileWriter writer;
    private final ObjectMapper yaml = YamlMappers.get();

    public BucketMetadataStore(
            final StorageLayout layout,
            final AtomicFileWriter writer) {
        this.layout = layout;
        this.writer = writer;
    }

    /** Writes (or overwrites) the bucket metadata YAML atomically. */
    public void write(final BucketMetadata meta) throws IOException {
        final Path target = layout.bucketMetaFile(meta.name());
        writer.write(target, out -> {
            try {
                yaml.writeValue(out, meta);
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    /** Reads the bucket metadata. Returns empty if the bucket does not exist. */
    public Optional<BucketMetadata> read(final String bucket) {
        final Path path = layout.bucketMetaFile(bucket);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(yaml.readValue(path.toFile(), BucketMetadata.class));
        } catch (final IOException ex) {
            LOG.warn("Failed to read bucket metadata for '{}': {}", bucket, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Returns {@code true} if the bucket metadata file exists. */
    public boolean exists(final String bucket) {
        return Files.exists(layout.bucketMetaFile(bucket));
    }

    /**
     * Lists all buckets by scanning {@code <base_dir>/buckets/}.
     * Buckets with unreadable metadata are skipped with a warning.
     */
    public List<BucketMetadata> listAll() throws IOException {
        final Path bucketsDir = layout.bucketsDir();
        final var result = new ArrayList<BucketMetadata>();
        if (!Files.exists(bucketsDir)) {
            return result;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(bucketsDir)) {
            for (final Path entry : ds) {
                if (!Files.isDirectory(entry)) continue;
                final String name = entry.getFileName().toString();
                final Optional<BucketMetadata> meta = read(name);
                meta.ifPresent(result::add);
            }
        }
        return result;
    }

    /**
     * Returns {@code true} if the bucket's data directory contains no objects.
     * Does NOT lock — caller must hold the bucket write lock.
     */
    public boolean isEmpty(final String bucket) throws IOException {
        final Path dataDir = layout.bucketDataDir(bucket);
        final Path objectsDir = layout.bucketObjectsMetaDir(bucket);
        return isEmptyDir(dataDir) && isEmptyDir(objectsDir);
    }

    private static boolean isEmptyDir(final Path dir) throws IOException {
        if (!Files.exists(dir)) return true;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            return !ds.iterator().hasNext();
        }
    }
}
