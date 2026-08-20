/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

final class PartStoreStreamingTest {

    @TempDir
    Path tempDir;

    @Test
    void streamsPartToDiskAndCalculatesEtagWithoutMaterializingTheBody() throws Exception {
        final int size = 12 * 1024 * 1024 + 37;
        final PartStore store = new PartStore(
                new StorageLayout(tempDir), new AtomicFileWriter(false));

        final ObjectPayloadStore.WriteResult result =
                store.write("upload-id", 1, new PatternInputStream(size));

        assertThat(result.size()).isEqualTo(size);
        assertThat(result.etag()).isEqualTo(expectedEtag(size));
        assertThat(Files.size(store.partPath("upload-id", 1))).isEqualTo(size);
        assertThat(Files.readAllBytes(store.partPath("upload-id", 1)))
                .containsOnly((byte) 0x5a);
    }

    private static String expectedEtag(final int size) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("MD5");
        final byte[] block = new byte[8192];
        java.util.Arrays.fill(block, (byte) 0x5a);
        int remaining = size;
        while (remaining > 0) {
            final int count = Math.min(remaining, block.length);
            digest.update(block, 0, count);
            remaining -= count;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class PatternInputStream extends InputStream {
        private int remaining;

        private PatternInputStream(final int remaining) {
            this.remaining = remaining;
        }

        @Override
        public int read() {
            throw new AssertionError("The streaming writer must use buffered reads");
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            if (remaining == 0) return -1;
            final int count = Math.min(remaining, length);
            java.util.Arrays.fill(buffer, offset, offset + count, (byte) 0x5a);
            remaining -= count;
            return count;
        }
    }
}
