/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.core.object;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata;
import org.midnightbsd.leonardo.core.bucket.BucketMetadataStore;
import org.midnightbsd.leonardo.core.bucket.BucketService;
import org.midnightbsd.leonardo.storage.AtomicFileWriter;
import org.midnightbsd.leonardo.storage.ObjectPayloadStore;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Payload housekeeping and null-version semantics of {@link ObjectPublisher}. */
final class ObjectPublisherTest {

    @TempDir
    Path tempDir;

    private StorageLayout layout;
    private BucketService bucketService;
    private BucketMetadataStore bucketStore;
    private ObjectMetadataStore metaStore;
    private ObjectService service;

    @BeforeEach
    void setUp() throws Exception {
        layout = new StorageLayout(tempDir);
        final AtomicFileWriter writer = new AtomicFileWriter(false);
        bucketStore = new BucketMetadataStore(layout, writer);
        bucketService = new BucketService(bucketStore, layout, "us-east-1");
        bucketService.createBucket("bucket", "owner", null, false);
        metaStore = new ObjectMetadataStore(layout, writer);
        final ObjectPayloadStore payloadStore = new ObjectPayloadStore(layout, writer);
        service = new ObjectService(metaStore, bucketStore, payloadStore,
                new ObjectPublisher(metaStore, bucketStore, payloadStore));
    }

    private void put(final String key, final String content) throws Exception {
        service.putObject("bucket", key, null, "text/plain", null, null, null, null, null,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private void setVersioning(final BucketMetadata.VersioningStatus status) throws Exception {
        bucketStore.write(bucketStore.read("bucket").orElseThrow()
                .withVersioning(new BucketMetadata.VersioningConfig(status, false)));
    }

    private long payloadFiles() throws Exception {
        final Path dataDir = layout.bucketDataDir("bucket");
        if (!Files.exists(dataDir)) return 0;
        try (Stream<Path> files = Files.list(dataDir)) {
            return files.count();
        }
    }

    @Test
    void overwritingAnUnversionedObjectRemovesTheOldPayload() throws Exception {
        put("k", "one");
        put("k", "two");
        put("k", "three");

        assertThat(payloadFiles()).isEqualTo(1);
        assertThat(metaStore.read("bucket", "k")).get().extracting(ObjectMetadata::size).isEqualTo(5L);

        service.deleteObject("bucket", "k", false);
        assertThat(payloadFiles()).isZero();
        bucketService.deleteBucket("bucket");   // no stray payload blocks the delete
    }

    @Test
    void putOnASuspendedBucketKeepsRealVersionsAndReplacesTheNullVersion() throws Exception {
        setVersioning(BucketMetadata.VersioningStatus.ENABLED);
        put("k", "v1");
        put("k", "v2");
        setVersioning(BucketMetadata.VersioningStatus.SUSPENDED);

        put("k", "null-1");
        ObjectMetadata meta = metaStore.read("bucket", "k").orElseThrow();
        assertThat(meta.versionId()).isNull();
        assertThat(meta.versions()).extracting(ObjectMetadata.ObjectVersion::versionId)
                .allSatisfy(id -> assertThat(id).isNotEqualTo("null"))
                .hasSize(2);
        assertThat(payloadFiles()).isEqualTo(3);

        put("k", "null-2");
        meta = metaStore.read("bucket", "k").orElseThrow();
        assertThat(meta.versions()).hasSize(2);
        assertThat(payloadFiles()).isEqualTo(3);   // the earlier null version is gone

        // DELETE without a version id inserts a null delete marker over the null version
        final ObjectService.DeleteObjectResult del = service.deleteObject("bucket", "k", false);
        assertThat(del.deleteMarkerVersionId()).isEqualTo("null");
        meta = metaStore.read("bucket", "k").orElseThrow();
        assertThat(meta.isDeleteMarker()).isTrue();
        assertThat(meta.versionId()).isNull();
        assertThat(meta.versions()).hasSize(2);
        assertThat(payloadFiles()).isEqualTo(2);

        // A second DELETE replaces the null marker rather than stacking another one
        service.deleteObject("bucket", "k", false);
        meta = metaStore.read("bucket", "k").orElseThrow();
        assertThat(meta.versions()).hasSize(2);
        assertThat(meta.versions()).extracting(ObjectMetadata.ObjectVersion::versionId)
                .doesNotContain("null");
    }

    @Test
    void historyForNullVersionDropsEveryNullEntry() {
        final var now = java.time.Instant.EPOCH;
        final ObjectMetadata prev = new ObjectMetadata("k", "cur", 1, "t", "e", now, now, "STANDARD",
                List.of(new ObjectMetadata.ObjectVersion("null", "old-null", 1, "e", false, now),
                        new ObjectMetadata.ObjectVersion("v1", "real", 1, "e", false, now)),
                null, null, "private", false, null, "v2", null, null, null);
        final var replaced = new java.util.ArrayList<String>();
        final List<ObjectMetadata.ObjectVersion> history = ObjectPublisher.historyForNullVersion(prev, replaced);
        assertThat(history).extracting(ObjectMetadata.ObjectVersion::versionId).containsExactly("v2", "v1");
        assertThat(replaced).containsExactly("old-null");
    }
}
