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
 * Reads and writes in-progress multipart upload state as YAML files under
 * {@code <base_dir>/buckets/<bucket>/_meta/uploads/<upload-id>.yaml}.
 */
@Singleton
public final class MultipartUploadStore {

    private static final Logger LOG = LoggerFactory.getLogger(MultipartUploadStore.class);

    private final StorageLayout layout;
    private final AtomicFileWriter writer;
    private final ObjectMapper yaml = YamlMappers.get();

    public MultipartUploadStore(
            final StorageLayout layout,
            final AtomicFileWriter writer) {
        this.layout = layout;
        this.writer = writer;
    }

    /** Writes (or overwrites) the upload state YAML atomically. */
    public void write(final MultipartUpload upload) throws IOException {
        final Path target = layout.multipartUploadMetaFile(upload.bucket(), upload.uploadId());
        writer.write(target, out -> {
            try {
                yaml.writeValue(out, upload);
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    /** Reads upload state. Returns empty if the YAML does not exist. */
    public Optional<MultipartUpload> read(final String bucket, final String uploadId) {
        final Path path = layout.multipartUploadMetaFile(bucket, uploadId);
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(yaml.readValue(path.toFile(), MultipartUpload.class));
        } catch (final IOException ex) {
            LOG.warn("Failed to read upload metadata '{}/{}': {}", bucket, uploadId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Returns {@code true} if the upload state file exists. */
    public boolean exists(final String bucket, final String uploadId) {
        return Files.exists(layout.multipartUploadMetaFile(bucket, uploadId));
    }

    /** Deletes the upload state YAML. Silently succeeds if it does not exist. */
    public void delete(final String bucket, final String uploadId) throws IOException {
        Files.deleteIfExists(layout.multipartUploadMetaFile(bucket, uploadId));
    }

    /** Lists all in-progress uploads for {@code bucket}. */
    public List<MultipartUpload> listAll(final String bucket) throws IOException {
        final Path uploadsDir = layout.bucketUploadsMetaDir(bucket);
        final var uploads = new ArrayList<MultipartUpload>();
        if (!Files.exists(uploadsDir)) return uploads;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(uploadsDir, "*.yaml")) {
            for (final Path p : ds) {
                try {
                    uploads.add(yaml.readValue(p.toFile(), MultipartUpload.class));
                } catch (final IOException ex) {
                    LOG.warn("Skipping unreadable upload metadata '{}': {}", p, ex.getMessage());
                }
            }
        }
        return uploads;
    }
}
