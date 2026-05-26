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
package org.midnightbsd.leonardo.storage;

import jakarta.inject.Singleton;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Stores and retrieves multipart upload parts under
 * {@code <base_dir>/tmp/multipart/<upload-id>/part-NNNNN}.
 *
 * <p>Part files are written atomically via {@link AtomicFileWriter}. An
 * {@code UploadPart} with the same part number overwrites any previously
 * written part, which is the correct S3 behaviour.
 */
@Singleton
public final class PartStore {

    private final StorageLayout layout;
    private final AtomicFileWriter writer;

    public PartStore(final StorageLayout layout, final AtomicFileWriter writer) {
        this.layout = layout;
        this.writer = writer;
    }

    /**
     * Writes {@code data} as part {@code partNumber} of upload {@code uploadId}.
     * Returns the MD5 hex digest of the part bytes (the part ETag, without quotes).
     */
    public String write(
            final String uploadId,
            final int partNumber,
            final byte[] data) throws IOException {
        final Path target = partPath(uploadId, partNumber);
        writer.write(target, out -> {
            try {
                out.write(data);
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        return ObjectPayloadStore.md5Hex(data);
    }

    /** Reads all bytes of the stored part. */
    public byte[] read(final String uploadId, final int partNumber) throws IOException {
        return Files.readAllBytes(partPath(uploadId, partNumber));
    }

    /** Returns {@code true} if the part file exists. */
    public boolean exists(final String uploadId, final int partNumber) {
        return Files.exists(partPath(uploadId, partNumber));
    }

    /** Returns the path to the part file (does not check existence). */
    public Path partPath(final String uploadId, final int partNumber) {
        return layout.multipartUploadPartsDir(uploadId)
                .resolve(String.format("part-%05d", partNumber));
    }

    /**
     * Deletes all part files and the directory for {@code uploadId}.
     * Silently succeeds if the directory does not exist.
     */
    public void deleteUpload(final String uploadId) throws IOException {
        final Path dir = layout.multipartUploadPartsDir(uploadId);
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path d, final IOException ex)
                    throws IOException {
                if (ex != null) throw ex;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
