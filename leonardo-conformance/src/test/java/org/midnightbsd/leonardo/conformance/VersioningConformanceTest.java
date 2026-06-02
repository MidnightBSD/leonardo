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
package org.midnightbsd.leonardo.conformance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance tests for Phase 4+5 object versioning: enabling versioning,
 * version creation on PUT, version-aware GET/DELETE, ListObjectVersions,
 * and delete-marker creation.
 */
final class VersioningConformanceTest extends ConformanceBase {

    private static final String KEY = "versioned-object";

    @BeforeEach
    void enableVersioning() {
        s3.putBucketVersioning(r -> r.bucket(bucket).versioningConfiguration(v ->
                v.status(BucketVersioningStatus.ENABLED)));
    }

    // -------------------------------------------------------------------------
    // Version creation
    // -------------------------------------------------------------------------

    @Test
    void putObjectReturnsVersionId() {
        final PutObjectResponse r1 = s3.putObject(
                req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("v1".getBytes()));
        assertThat(r1.versionId()).isNotBlank();
    }

    @Test
    void twoPutsProduceDifferentVersionIds() {
        final PutObjectResponse r1 = s3.putObject(
                req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("version-1".getBytes()));
        final PutObjectResponse r2 = s3.putObject(
                req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("version-2".getBytes()));

        assertThat(r1.versionId()).isNotBlank();
        assertThat(r2.versionId()).isNotBlank();
        assertThat(r1.versionId()).isNotEqualTo(r2.versionId());
    }

    // -------------------------------------------------------------------------
    // Version-aware GET
    // -------------------------------------------------------------------------

    @Test
    void getByVersionIdReturnsCorrectContent() {
        final PutObjectResponse r1 = s3.putObject(
                req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("content-v1".getBytes()));
        s3.putObject(req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("content-v2".getBytes()));

        final ResponseBytes<GetObjectResponse> v1Body = s3.getObjectAsBytes(
                req -> req.bucket(bucket).key(KEY).versionId(r1.versionId()));
        assertThat(new String(v1Body.asByteArray())).isEqualTo("content-v1");
    }

    @Test
    void getWithoutVersionIdReturnsLatest() {
        s3.putObject(req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("first".getBytes()));
        s3.putObject(req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("second".getBytes()));

        final ResponseBytes<GetObjectResponse> latest = s3.getObjectAsBytes(
                req -> req.bucket(bucket).key(KEY));
        assertThat(new String(latest.asByteArray())).isEqualTo("second");
    }

    // -------------------------------------------------------------------------
    // ListObjectVersions
    // -------------------------------------------------------------------------

    @Test
    void listObjectVersionsShowsAllVersions() {
        s3.putObject(req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("v1".getBytes()));
        s3.putObject(req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("v2".getBytes()));
        s3.putObject(req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("v3".getBytes()));

        final ListObjectVersionsResponse versions =
                s3.listObjectVersions(r -> r.bucket(bucket).prefix(KEY));
        assertThat(versions.versions()).hasSize(3);
        assertThat(versions.versions()).allMatch(v -> v.key().equals(KEY));
    }

    // -------------------------------------------------------------------------
    // Delete creates delete marker
    // -------------------------------------------------------------------------

    @Test
    void deleteWithoutVersionIdCreatesDeleteMarker() {
        s3.putObject(req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("content".getBytes()));
        s3.deleteObject(r -> r.bucket(bucket).key(KEY));

        // HEAD should now return 404 (delete marker is the latest version)
        assertThatThrownBy(() -> s3.headObject(r -> r.bucket(bucket).key(KEY)))
                .isInstanceOf(NoSuchKeyException.class);

        // But the version history should contain a delete marker
        final ListObjectVersionsResponse history =
                s3.listObjectVersions(r -> r.bucket(bucket).prefix(KEY));
        final List<DeleteMarkerEntry> markers = history.deleteMarkers();
        assertThat(markers).hasSize(1);
        assertThat(markers.get(0).key()).isEqualTo(KEY);
    }

    @Test
    void deleteSpecificVersionRemovesOnlyThatVersion() {
        final PutObjectResponse r1 = s3.putObject(
                req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("v1".getBytes()));
        final PutObjectResponse r2 = s3.putObject(
                req -> req.bucket(bucket).key(KEY),
                RequestBody.fromBytes("v2".getBytes()));

        // Delete v1 specifically
        s3.deleteObject(r -> r.bucket(bucket).key(KEY).versionId(r1.versionId()));

        // v2 should still be the current version
        final ResponseBytes<GetObjectResponse> current =
                s3.getObjectAsBytes(req -> req.bucket(bucket).key(KEY));
        assertThat(new String(current.asByteArray())).isEqualTo("v2");

        // Only one version in history (v1 was deleted)
        final ListObjectVersionsResponse history =
                s3.listObjectVersions(r -> r.bucket(bucket).prefix(KEY));
        final List<ObjectVersion> remaining = history.versions();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).versionId()).isEqualTo(r2.versionId());
    }
}
