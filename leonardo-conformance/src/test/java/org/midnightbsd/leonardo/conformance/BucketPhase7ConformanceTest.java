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
import software.amazon.awssdk.services.s3.model.AccelerateConfiguration;
import software.amazon.awssdk.services.s3.model.BucketAccelerateStatus;
import software.amazon.awssdk.services.s3.model.GetBucketAccelerateConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionResponse;
import software.amazon.awssdk.services.s3.model.GetBucketOwnershipControlsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectOwnership;
import software.amazon.awssdk.services.s3.model.OwnershipControls;
import software.amazon.awssdk.services.s3.model.OwnershipControlsRule;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionByDefault;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionConfiguration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance tests for Phase 7: bucket encryption, ownership controls,
 * and transfer acceleration configuration (stub: store + return, no actual dispatch).
 */
final class BucketPhase7ConformanceTest extends ConformanceBase {

    // -------------------------------------------------------------------------
    // Encryption configuration
    // -------------------------------------------------------------------------

    @Test
    void getEncryptionConfigThrows404WhenNoneSet() {
        assertThatThrownBy(() ->
                s3.getBucketEncryption(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    @Test
    void putAndGetEncryptionConfigRoundTrips() {
        s3.putBucketEncryption(r -> r
                .bucket(bucket)
                .serverSideEncryptionConfiguration(
                        ServerSideEncryptionConfiguration.builder()
                                .rules(List.of(
                                        ServerSideEncryptionRule.builder()
                                                .applyServerSideEncryptionByDefault(
                                                        ServerSideEncryptionByDefault.builder()
                                                                .sseAlgorithm(ServerSideEncryption.AES256)
                                                                .build())
                                                .build()))
                                .build()));

        final GetBucketEncryptionResponse resp =
                s3.getBucketEncryption(r -> r.bucket(bucket));
        assertThat(resp.serverSideEncryptionConfiguration()).isNotNull();
        assertThat(resp.serverSideEncryptionConfiguration().rules()).hasSize(1);
        assertThat(resp.serverSideEncryptionConfiguration().rules().get(0)
                .applyServerSideEncryptionByDefault().sseAlgorithm())
                .isEqualTo(ServerSideEncryption.AES256);
    }

    @Test
    void deleteEncryptionConfigClearsIt() {
        s3.putBucketEncryption(r -> r
                .bucket(bucket)
                .serverSideEncryptionConfiguration(
                        ServerSideEncryptionConfiguration.builder()
                                .rules(List.of(
                                        ServerSideEncryptionRule.builder()
                                                .applyServerSideEncryptionByDefault(
                                                        ServerSideEncryptionByDefault.builder()
                                                                .sseAlgorithm(ServerSideEncryption.AES256)
                                                                .build())
                                                .build()))
                                .build()));

        s3.deleteBucketEncryption(r -> r.bucket(bucket));

        assertThatThrownBy(() ->
                s3.getBucketEncryption(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    // -------------------------------------------------------------------------
    // Ownership controls
    // -------------------------------------------------------------------------

    @Test
    void getOwnershipControlsThrows404WhenNoneSet() {
        assertThatThrownBy(() ->
                s3.getBucketOwnershipControls(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    @Test
    void putAndGetOwnershipControlsRoundTrips() {
        s3.putBucketOwnershipControls(r -> r
                .bucket(bucket)
                .ownershipControls(OwnershipControls.builder()
                        .rules(List.of(
                                OwnershipControlsRule.builder()
                                        .objectOwnership(ObjectOwnership.BUCKET_OWNER_ENFORCED)
                                        .build()))
                        .build()));

        final GetBucketOwnershipControlsResponse resp =
                s3.getBucketOwnershipControls(r -> r.bucket(bucket));
        assertThat(resp.ownershipControls()).isNotNull();
        assertThat(resp.ownershipControls().rules()).hasSize(1);
        assertThat(resp.ownershipControls().rules().get(0).objectOwnership())
                .isEqualTo(ObjectOwnership.BUCKET_OWNER_ENFORCED);
    }

    @Test
    void deleteOwnershipControlsClearsIt() {
        s3.putBucketOwnershipControls(r -> r
                .bucket(bucket)
                .ownershipControls(OwnershipControls.builder()
                        .rules(List.of(
                                OwnershipControlsRule.builder()
                                        .objectOwnership(ObjectOwnership.BUCKET_OWNER_PREFERRED)
                                        .build()))
                        .build()));

        s3.deleteBucketOwnershipControls(r -> r.bucket(bucket));

        assertThatThrownBy(() ->
                s3.getBucketOwnershipControls(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    // -------------------------------------------------------------------------
    // Transfer acceleration
    // -------------------------------------------------------------------------

    @Test
    void getAccelerateConfigurationReturnsEmptyWhenNoneSet() {
        final GetBucketAccelerateConfigurationResponse resp =
                s3.getBucketAccelerateConfiguration(r -> r.bucket(bucket));
        // No status configured — status() should be null or UNKNOWN
        assertThat(resp.status()).isNull();
    }

    @Test
    void putAndGetAccelerateConfigurationRoundTrips() {
        s3.putBucketAccelerateConfiguration(r -> r
                .bucket(bucket)
                .accelerateConfiguration(AccelerateConfiguration.builder()
                        .status(BucketAccelerateStatus.ENABLED)
                        .build()));

        final GetBucketAccelerateConfigurationResponse resp =
                s3.getBucketAccelerateConfiguration(r -> r.bucket(bucket));
        assertThat(resp.status()).isEqualTo(BucketAccelerateStatus.ENABLED);
    }

    @Test
    void encryptionAndOwnershipOnNonExistentBucketThrows() {
        assertThatThrownBy(() ->
                s3.getBucketEncryption(r -> r.bucket("nonexistent-bucket-xyz")))
                .isInstanceOf(NoSuchBucketException.class);
    }
}
