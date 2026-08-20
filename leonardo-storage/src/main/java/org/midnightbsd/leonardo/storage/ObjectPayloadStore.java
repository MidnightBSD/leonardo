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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

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

    /** Result of a streamed write. */
    public record WriteResult(String etag, long size) {}

    /** Streams an object payload to disk while calculating its ETag and size. */
    public WriteResult write(final String bucket, final String objectId, final InputStream data)
            throws IOException {
        final Path target = layout.objectDataFile(bucket, objectId);
        final java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("MD5");
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 unavailable", ex);
        }
        final long[] size = {0};
        writer.write(target, out -> {
            try {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = data.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    size[0] += read;
                }
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        return new WriteResult(HexFormat.of().formatHex(digest.digest()), size[0]);
    }

    /** Opens a stored payload for streaming verification or delivery. */
    public InputStream open(final String bucket, final String objectId) throws IOException {
        return Files.newInputStream(layout.objectDataFile(bucket, objectId));
    }

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
    // Multipart assembly
    // -------------------------------------------------------------------------

    /**
     * Assembles a multipart object by streaming {@code partPaths} sequentially into
     * the final data file. Uses {@link FileChannel#transferTo} to avoid buffering
     * the entire payload in memory. Returns the total assembled byte count.
     */
    public long writeFromParts(
            final String bucket,
            final String objectId,
            final List<Path> partPaths) throws IOException {

        long total = 0;
        for (final Path p : partPaths) {
            total += Files.size(p);
        }
        final long totalSize = total;

        writer.write(layout.objectDataFile(bucket, objectId), out -> {
            try {
                final var dst = Channels.newChannel(out);
                for (final Path partPath : partPaths) {
                    try (FileChannel src = FileChannel.open(partPath, StandardOpenOption.READ)) {
                        long remaining = src.size();
                        long position = 0;
                        while (remaining > 0) {
                            final long n = src.transferTo(position, remaining, dst);
                            if (n <= 0) break;
                            position  += n;
                            remaining -= n;
                        }
                    }
                }
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        return totalSize;
    }

    /**
     * Computes the multipart ETag: MD5 of the concatenated raw MD5 bytes of each
     * part (in order), appended with {@code -<partCount>}.
     *
     * <p>Each element of {@code partEtagHex} must be a 32-character lowercase hex
     * string (the part MD5 returned by {@code UploadPart}).
     */
    public static String computeMultipartEtag(final List<String> partEtagHex) {
        try {
            final MessageDigest md = MessageDigest.getInstance("MD5");
            for (final String hex : partEtagHex) {
                md.update(HexFormat.of().parseHex(hex));
            }
            return "\"" + HexFormat.of().formatHex(md.digest()) + "-" + partEtagHex.size() + "\"";
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 unavailable", ex);
        }
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
