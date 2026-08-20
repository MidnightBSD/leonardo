/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.core.object;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.midnightbsd.leonardo.core.bucket.BucketMetadataStore;
import org.midnightbsd.leonardo.core.bucket.BucketService;
import org.midnightbsd.leonardo.storage.AtomicFileWriter;
import org.midnightbsd.leonardo.storage.ObjectPayloadStore;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class ObjectServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deletingMissingKeyInVersionedBucketCreatesDeleteMarker() throws Exception {
        final StorageLayout layout = new StorageLayout(tempDir);
        final AtomicFileWriter writer = new AtomicFileWriter(false);
        final BucketMetadataStore bucketStore = new BucketMetadataStore(layout, writer);
        new BucketService(bucketStore, layout, "us-east-1")
                .createBucket("bucket", "owner", null, true);
        final ObjectMetadataStore metaStore = new ObjectMetadataStore(layout, writer);
        final ObjectService service = new ObjectService(
                metaStore, bucketStore, new ObjectPayloadStore(layout, writer));

        final ObjectService.DeleteObjectResult result = service.deleteObject("bucket", "missing", false);

        assertThat(result.deleteMarkerVersionId()).isNotBlank();
        assertThat(metaStore.read("bucket", "missing")).get()
                .extracting(ObjectMetadata::versionId).isEqualTo(result.deleteMarkerVersionId());
    }
}
