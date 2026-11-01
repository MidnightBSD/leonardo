/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.core.object;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.midnightbsd.leonardo.storage.AtomicFileWriter;
import org.midnightbsd.leonardo.storage.layout.StorageLayout;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class ObjectMetadataStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void resumesVersionListingWithinTheSameKey() throws Exception {
        final ObjectMetadataStore store = new ObjectMetadataStore(
                new StorageLayout(tempDir), new AtomicFileWriter(true));
        final Instant now = Instant.parse("2026-08-19T00:00:00Z");
        store.write("bucket", new ObjectMetadata(
                "key", "current", 1, "text/plain", "current-etag", now, now, "STANDARD",
                List.of(
                        new ObjectMetadata.ObjectVersion("v2", "v2-data", 1,
                                "v2-etag", false, now.minusSeconds(1)),
                        new ObjectMetadata.ObjectVersion("v1", "v1-data", 1,
                                "v1-etag", false, now.minusSeconds(2))),
                null, null, "private", false, null, "v3", null, null, null));

        final ObjectMetadataStore.ListVersionsPage first =
                store.listVersions("bucket", null, null, 1, null, null);
        assertThat(first.versions()).extracting(ObjectMetadataStore.VersionEntry::versionId)
                .containsExactly("v3");
        assertThat(first.nextKeyMarker()).isEqualTo("key");
        assertThat(first.nextVersionIdMarker()).isEqualTo("v3");

        final ObjectMetadataStore.ListVersionsPage second = store.listVersions(
                "bucket", null, null, 10,
                first.nextKeyMarker(), first.nextVersionIdMarker());
        assertThat(second.versions()).extracting(ObjectMetadataStore.VersionEntry::versionId)
                .containsExactly("v2", "v1");
    }

    @Test
    void listHidesKeysWhoseCurrentVersionIsADeleteMarker() throws Exception {
        final ObjectMetadataStore store = new ObjectMetadataStore(
                new StorageLayout(tempDir), new AtomicFileWriter(false));
        final Instant now = Instant.parse("2026-10-31T00:00:00Z");
        store.write("bucket", new ObjectMetadata(
                "alive", "obj-1", 1, "text/plain", "\"e1\"", now, now, "STANDARD",
                List.of(), null, null, "private", false, null, "v1", null, null, null));
        // A delete marker has no payload: objectId is empty, history keeps the old version.
        store.write("bucket", new ObjectMetadata(
                "gone", "", 0, null, null, now, now, "STANDARD",
                List.of(new ObjectMetadata.ObjectVersion("v1", "obj-2", 1, "\"e2\"", false, now)),
                null, null, "private", false, null, "v2", null, null, null));

        final ObjectMetadataStore.ListPage page = store.list("bucket", null, null, 1000, null);
        assertThat(page.objects()).extracting(ObjectMetadata::key).containsExactly("alive");
        assertThat(page.truncated()).isFalse();

        // The hidden key still shows up as a delete marker in the version listing.
        final ObjectMetadataStore.ListVersionsPage versions =
                store.listVersions("bucket", null, null, 1000, null, null);
        assertThat(versions.versions())
                .filteredOn(ObjectMetadataStore.VersionEntry::deleteMarker)
                .extracting(ObjectMetadataStore.VersionEntry::key)
                .containsExactly("gone");
    }

    @Test
    void readVersionTreatsTheLiteralNullAsTheUnversionedId() throws Exception {
        final ObjectMetadataStore store = new ObjectMetadataStore(
                new StorageLayout(tempDir), new AtomicFileWriter(false));
        final Instant now = Instant.parse("2026-10-31T00:00:00Z");
        store.write("bucket", new ObjectMetadata(
                "key", "obj-1", 1, "text/plain", "\"e1\"", now, now, "STANDARD",
                List.of(), null, null, "private", false, null, null, null, null, null));

        assertThat(store.readVersion("bucket", "key", "null")).isPresent();
        assertThat(store.readVersion("bucket", "key", "01HXDOESNOTEXIST")).isEmpty();
        assertThat(ObjectMetadataStore.sameVersionId("null", null)).isTrue();
        assertThat(ObjectMetadataStore.sameVersionId(null, "null")).isTrue();
        assertThat(ObjectMetadataStore.sameVersionId("v1", null)).isFalse();
    }
}
