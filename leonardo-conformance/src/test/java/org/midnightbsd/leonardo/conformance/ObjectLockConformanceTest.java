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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectLegalHoldResponse;
import software.amazon.awssdk.services.s3.model.GetObjectLockConfigurationResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHold;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance tests for Phase 5 object locking: object lock configuration,
 * legal hold (enables/blocks delete), and retention (compliance mode blocks
 * delete before the retain-until date).
 *
 * <p>Object lock must be enabled at bucket creation time, so each test in this
 * class creates its own dedicated bucket via {@link #setupLockedBucket()} rather
 * than using the one from {@link ConformanceBase#createBucket()}.
 */
final class ObjectLockConformanceTest extends ConformanceBase {

    /** Bucket with object lock enabled at creation time. */
    private String lockedBucket;

    @BeforeEach
    void setupLockedBucket() {
        lockedBucket = "cf-locked-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        s3.createBucket(r -> r.bucket(lockedBucket).objectLockEnabledForBucket(true));
    }

    @AfterEach
    void teardownLockedBucket() {
        deleteBucketFully(lockedBucket);
    }

    // -------------------------------------------------------------------------
    // Object lock configuration
    // -------------------------------------------------------------------------

    @Test
    void getObjectLockConfigurationReflectsEnabledState() {
        final GetObjectLockConfigurationResponse cfg =
                s3.getObjectLockConfiguration(r -> r.bucket(lockedBucket));
        assertThat(cfg.objectLockConfiguration().objectLockEnabled())
                .isEqualTo(ObjectLockEnabled.ENABLED);
    }

    @Test
    void putObjectLockConfigurationDefaultRetention() {
        s3.putObjectLockConfiguration(r -> r.bucket(lockedBucket)
                .objectLockConfiguration(c -> c
                        .objectLockEnabled(ObjectLockEnabled.ENABLED)
                        .rule(rule -> rule.defaultRetention(ret -> ret
                                .mode(ObjectLockRetentionMode.COMPLIANCE)
                                .days(1)))));

        final GetObjectLockConfigurationResponse got =
                s3.getObjectLockConfiguration(r -> r.bucket(lockedBucket));
        assertThat(got.objectLockConfiguration().rule().defaultRetention().days()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Legal hold
    // -------------------------------------------------------------------------

    @Test
    void legalHoldPreventsDeleteOfCurrentVersion() {
        final PutObjectResponse put = s3.putObject(
                r -> r.bucket(lockedBucket).key("held.txt"),
                RequestBody.fromBytes("data".getBytes()));
        final String vid = put.versionId();

        s3.putObjectLegalHold(r -> r.bucket(lockedBucket).key("held.txt")
                .versionId(vid)
                .legalHold(ObjectLockLegalHold.builder()
                        .status(ObjectLockLegalHoldStatus.ON).build()));

        assertThatThrownBy(() ->
                s3.deleteObject(r -> r.bucket(lockedBucket).key("held.txt").versionId(vid)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(403));
    }

    @Test
    void legalHoldCanBeRemovedAllowingDelete() {
        final PutObjectResponse put = s3.putObject(
                r -> r.bucket(lockedBucket).key("held2.txt"),
                RequestBody.fromBytes("data".getBytes()));
        final String vid = put.versionId();

        s3.putObjectLegalHold(r -> r.bucket(lockedBucket).key("held2.txt")
                .versionId(vid)
                .legalHold(ObjectLockLegalHold.builder()
                        .status(ObjectLockLegalHoldStatus.ON).build()));

        final GetObjectLegalHoldResponse held =
                s3.getObjectLegalHold(r -> r.bucket(lockedBucket).key("held2.txt").versionId(vid));
        assertThat(held.legalHold().status()).isEqualTo(ObjectLockLegalHoldStatus.ON);

        // Remove the hold
        s3.putObjectLegalHold(r -> r.bucket(lockedBucket).key("held2.txt")
                .versionId(vid)
                .legalHold(ObjectLockLegalHold.builder()
                        .status(ObjectLockLegalHoldStatus.OFF).build()));

        // Delete should now succeed
        s3.deleteObject(r -> r.bucket(lockedBucket).key("held2.txt").versionId(vid));
    }

    // -------------------------------------------------------------------------
    // Retention
    // -------------------------------------------------------------------------

    @Test
    void retentionInComplianceModeBlocksDeleteBeforeExpiry() {
        final PutObjectResponse put = s3.putObject(
                r -> r.bucket(lockedBucket).key("retained.txt"),
                RequestBody.fromBytes("payload".getBytes()));
        final String vid = put.versionId();

        // Retain for 1 day from now
        final Instant retainUntil = Instant.now().plus(1, ChronoUnit.DAYS);
        s3.putObjectRetention(r -> r.bucket(lockedBucket).key("retained.txt")
                .versionId(vid)
                .retention(ret -> ret
                        .mode(ObjectLockRetentionMode.COMPLIANCE)
                        .retainUntilDate(retainUntil)));

        assertThatThrownBy(() ->
                s3.deleteObject(r -> r.bucket(lockedBucket).key("retained.txt").versionId(vid)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(403));
    }
}
