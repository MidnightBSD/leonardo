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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

final class AtomicFileWriterTest {

    @Test
    void writesFileContents(@TempDir final Path dir) throws IOException {
        final AtomicFileWriter writer = new AtomicFileWriter(false);
        final Path target = dir.resolve("subdir/file.yaml");
        writer.write(target, out -> {
            try {
                out.write("hello world".getBytes());
            } catch (final IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        assertThat(target).exists();
        assertThat(Files.readString(target)).isEqualTo("hello world");
    }

    @Test
    void replacesExistingFile(@TempDir final Path dir) throws IOException {
        final AtomicFileWriter writer = new AtomicFileWriter(false);
        final Path target = dir.resolve("file.yaml");
        Files.writeString(target, "old content");

        writer.write(target, out -> {
            try {
                out.write("new content".getBytes());
            } catch (final IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        assertThat(Files.readString(target)).isEqualTo("new content");
    }

    @Test
    void leavesNoTempfileAfterSuccessfulWrite(@TempDir final Path dir) throws IOException {
        final AtomicFileWriter writer = new AtomicFileWriter(false);
        final Path target = dir.resolve("file.yaml");
        writer.write(target, out -> {
            try {
                out.write("x".getBytes());
            } catch (final IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        // Only the target should exist — no .tmp lingering.
        try (Stream<Path> entries = Files.list(dir)) {
            assertThat(entries.toList()).containsExactly(target);
        }
    }

    @Test
    void cleansUpTempfileOnWriterFailure(@TempDir final Path dir) {
        final AtomicFileWriter writer = new AtomicFileWriter(false);
        final Path target = dir.resolve("file.yaml");

        try {
            writer.write(target, out -> {
                throw new RuntimeException("simulated failure");
            });
        } catch (final RuntimeException | IOException expected) {
            // expected
        }

        try (Stream<Path> entries = Files.list(dir)) {
            assertThat(entries.toList()).isEmpty();
        } catch (final IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
