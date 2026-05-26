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
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stores and retrieves object payload bytes under
 * {@code <base_dir>/buckets/<bucket>/data/<object-id>}.
 *
 * <p>Writes are atomic (via {@link AtomicFileWriter}): the data file is
 * either fully written or not present at all — no partial state.
 *
 * <p>For M2 the full body is buffered in memory. Streaming via
 * {@code FileChannel.transferFrom} / {@code sendfile} is deferred to M3.
 */
@Singleton
public final class ObjectPayloadStore {

    private final StorageLayout layout;
    private final AtomicFileWriter writer;

    public ObjectPayloadStore(final StorageLayout layout, final AtomicFileWriter writer) {
        this.layout = layout;
        this.writer = writer;
    }

    /**
     * Writes {@code data} atomically. Returns the MD5 hex digest (no quotes),
     * suitable for use as an S3 ETag on simple (non-multipart) objects.
     */
    public String write(
            final String bucket, final String objectId, final byte[] data) throws IOException {
        final var target = layout.objectDataFile(bucket, objectId);
        writer.write(target, out -> {
            try {
                out.write(data);
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        return md5Hex(data);
    }

    /** Reads and returns all bytes of the stored payload. */
    public byte[] read(final String bucket, final String objectId) throws IOException {
        return Files.readAllBytes(layout.objectDataFile(bucket, objectId));
    }

    /** Returns {@code true} if the data file exists. */
    public boolean exists(final String bucket, final String objectId) {
        return Files.exists(layout.objectDataFile(bucket, objectId));
    }

    /** Deletes the data file. Silently succeeds if it doesn't exist. */
    public void delete(final String bucket, final String objectId) throws IOException {
        Files.deleteIfExists(layout.objectDataFile(bucket, objectId));
    }

    // -------------------------------------------------------------------------

    public static String md5Hex(final byte[] data) {
        try {
            final var md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 unavailable", ex);
        }
    }
}
