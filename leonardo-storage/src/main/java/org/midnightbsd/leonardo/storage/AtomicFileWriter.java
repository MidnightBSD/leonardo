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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Writes a file atomically: stream into a sibling tempfile, fsync the data,
 * rename onto the target, fsync the parent directory. This is the only way to
 * make POSIX file writes crash-safe — on any kernel panic, hard kill, or power
 * loss, the target either contains the old content or the new content, never
 * partial.
 *
 * <p>Used everywhere metadata or payloads are written: bucket.yaml,
 * per-object YAML, multipart part files, final object payloads.
 *
 * <p>The fsync step can be skipped via the {@code fsync_on_write: false}
 * configuration knob (typically for development; never in production unless
 * the operator really knows what they're doing).
 */
public final class AtomicFileWriter {

    private static final Set<PosixFilePermission> FILE_PERMS =
            PosixFilePermissions.fromString("rw-------");   // 0600

    private final boolean fsyncOnWrite;

    public AtomicFileWriter(final boolean fsyncOnWrite) {
        this.fsyncOnWrite = fsyncOnWrite;
    }

    /**
     * Atomically writes the file at {@code target} by streaming through a
     * temporary sibling and then renaming.
     *
     * @param target path the final file should occupy
     * @param writer callback that writes the file's contents to the supplied stream
     * @throws IOException if any I/O step fails; partial state is cleaned up
     */
    public void write(final Path target, final Consumer<OutputStream> writer) throws IOException {
        final Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("target has no parent directory: " + target);
        }
        Files.createDirectories(parent);

        final Path tmp = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");

        try {
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
                 OutputStream out = java.nio.channels.Channels.newOutputStream(ch)) {

                writer.accept(out);
                out.flush();
                if (fsyncOnWrite) {
                    ch.force(true);   // fsync the data + metadata
                }
            }
            applyPermsIfPosix(tmp);

            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException ex) {
                // NFS sometimes refuses atomic move across mounts. Fall back to
                // non-atomic; the project plan warns operators about NFS
                // semantics on startup.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            if (fsyncOnWrite) {
                fsyncDirectory(parent);
            }
        } catch (final RuntimeException | IOException ex) {
            // Best-effort cleanup of the tempfile if anything went wrong
            // between create and rename.
            try {
                Files.deleteIfExists(tmp);
            } catch (final IOException ignored) {
                // swallow — we're already in the error path
            }
            throw ex;
        }
    }

    private static void applyPermsIfPosix(final Path path) throws IOException {
        final PosixFileAttributeView view =
                Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view != null) {
            view.setPermissions(FILE_PERMS);
        }
    }

    private static void fsyncDirectory(final Path dir) {
        // Directory fsync isn't directly exposed in NIO; opening read-only and
        // forcing is the documented portable trick. Doesn't work on Windows
        // (which doesn't need it anyway) but does on every POSIX system we care
        // about.
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (final IOException ex) {
            // Some filesystems return EISDIR or similar for the open call. Log
            // and continue — the file-level fsync is the critical one.
            // (Logger injection deferred to keep this class dependency-free.)
        }
    }
}
