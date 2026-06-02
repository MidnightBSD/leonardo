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

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 9 conformance tests — metadata table configuration, CreateSession, ListDirectoryBuckets.
 * The non-SDK operations (CreateBucketMetadataConfiguration, UpdateBucketMetadataJournalTableConfiguration,
 * UpdateBucketMetadataInventoryTableConfiguration) are stubbed with NotImplemented and not tested here.
 */
final class BucketPhase9ConformanceTest extends ConformanceBase {

    // -------------------------------------------------------------------------
    // BucketMetadataTableConfiguration
    // -------------------------------------------------------------------------

    private static final String TABLE_BUCKET_ARN = "arn:aws:s3tables:us-east-1:123456789012:bucket/test-table-bucket";
    private static final String TABLE_NAME = "test-metadata-table";

    @Test
    void getMetadataTableConfigurationThrows404WhenNoneSet() {
        assertThatThrownBy(() ->
                s3.getBucketMetadataTableConfiguration(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    @Test
    void metadataTableConfigurationRoundTrip() {
        s3.createBucketMetadataTableConfiguration(r -> r
                .bucket(bucket)
                .metadataTableConfiguration(MetadataTableConfiguration.builder()
                        .s3TablesDestination(S3TablesDestination.builder()
                                .tableBucketArn(TABLE_BUCKET_ARN)
                                .tableName(TABLE_NAME)
                                .build())
                        .build()));

        final var resp = s3.getBucketMetadataTableConfiguration(r -> r.bucket(bucket));
        assertThat(resp.getBucketMetadataTableConfigurationResult()).isNotNull();
        final var dest = resp.getBucketMetadataTableConfigurationResult()
                .metadataTableConfigurationResult().s3TablesDestinationResult();
        assertThat(dest.tableBucketArn()).isEqualTo(TABLE_BUCKET_ARN);
        assertThat(dest.tableName()).isEqualTo(TABLE_NAME);
    }

    @Test
    void deleteMetadataTableConfigurationReturnsNoContent() {
        s3.createBucketMetadataTableConfiguration(r -> r
                .bucket(bucket)
                .metadataTableConfiguration(MetadataTableConfiguration.builder()
                        .s3TablesDestination(S3TablesDestination.builder()
                                .tableBucketArn(TABLE_BUCKET_ARN)
                                .tableName(TABLE_NAME)
                                .build())
                        .build()));

        s3.deleteBucketMetadataTableConfiguration(r -> r.bucket(bucket));

        assertThatThrownBy(() ->
                s3.getBucketMetadataTableConfiguration(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    // -------------------------------------------------------------------------
    // CreateSession
    // -------------------------------------------------------------------------

    @Test
    void createSessionReturnsCredentials() {
        final var resp = s3.createSession(r -> r.bucket(bucket));
        assertThat(resp.credentials()).isNotNull();
        assertThat(resp.credentials().accessKeyId()).isNotBlank();
        assertThat(resp.credentials().secretAccessKey()).isNotBlank();
        assertThat(resp.credentials().sessionToken()).isNotBlank();
        assertThat(resp.credentials().expiration()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // ListDirectoryBuckets
    // -------------------------------------------------------------------------

    @Test
    void listDirectoryBucketsReturnsEmptyList() {
        final var resp = s3.listDirectoryBuckets(r -> r.maxDirectoryBuckets(100));
        assertThat(resp.buckets()).isEmpty();
    }
}
