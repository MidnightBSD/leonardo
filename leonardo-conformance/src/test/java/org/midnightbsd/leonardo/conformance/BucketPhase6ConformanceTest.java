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
import software.amazon.awssdk.services.s3.model.BucketLoggingStatus;
import software.amazon.awssdk.services.s3.model.Destination;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.ReplicationRuleFilter;
import software.amazon.awssdk.services.s3.model.GetBucketLoggingResponse;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketReplicationResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NotificationConfiguration;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;
import software.amazon.awssdk.services.s3.model.ReplicationConfiguration;
import software.amazon.awssdk.services.s3.model.ReplicationRule;
import software.amazon.awssdk.services.s3.model.ReplicationRuleStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance tests for Phase 6: bucket notifications, server-access logging,
 * and replication configuration (stub: store + return, no actual dispatch).
 */
final class BucketPhase6ConformanceTest extends ConformanceBase {

    // -------------------------------------------------------------------------
    // Notification configuration
    // -------------------------------------------------------------------------

    @Test
    void getNotificationConfigurationReturnsEmptyWhenNoneSet() {
        final GetBucketNotificationConfigurationResponse resp =
                s3.getBucketNotificationConfiguration(r -> r.bucket(bucket));
        // No topic, queue, or lambda configurations — should all be empty
        assertThat(resp.topicConfigurations()).isEmpty();
        assertThat(resp.queueConfigurations()).isEmpty();
        assertThat(resp.lambdaFunctionConfigurations()).isEmpty();
    }

    @Test
    void putAndGetNotificationConfigurationRoundTrips() {
        final String queueArn = "arn:aws:sqs:us-east-1:123456789012:my-queue";
        s3.putBucketNotificationConfiguration(r -> r
                .bucket(bucket)
                .notificationConfiguration(nc -> nc
                        .queueConfigurations(List.of(
                                QueueConfiguration.builder()
                                        .id("test-rule")
                                        .queueArn(queueArn)
                                        .events(Event.S3_OBJECT_CREATED)
                                        .build()))));

        final GetBucketNotificationConfigurationResponse resp =
                s3.getBucketNotificationConfiguration(r -> r.bucket(bucket));
        assertThat(resp.queueConfigurations()).hasSize(1);
        assertThat(resp.queueConfigurations().get(0).queueArn()).isEqualTo(queueArn);
        assertThat(resp.queueConfigurations().get(0).id()).isEqualTo("test-rule");
    }

    @Test
    void putEmptyNotificationConfigurationClearsExisting() {
        // First set a config
        s3.putBucketNotificationConfiguration(r -> r
                .bucket(bucket)
                .notificationConfiguration(nc -> nc
                        .queueConfigurations(List.of(
                                QueueConfiguration.builder()
                                        .queueArn("arn:aws:sqs:us-east-1:123456789012:my-queue")
                                        .events(Event.S3_OBJECT_CREATED)
                                        .build()))));

        // Then clear it with an empty config
        s3.putBucketNotificationConfiguration(r -> r
                .bucket(bucket)
                .notificationConfiguration(NotificationConfiguration.builder().build()));

        final GetBucketNotificationConfigurationResponse resp =
                s3.getBucketNotificationConfiguration(r -> r.bucket(bucket));
        assertThat(resp.queueConfigurations()).isEmpty();
        assertThat(resp.topicConfigurations()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Logging configuration
    // -------------------------------------------------------------------------

    @Test
    void getBucketLoggingReturnsEmptyWhenNoneSet() {
        final GetBucketLoggingResponse resp =
                s3.getBucketLogging(r -> r.bucket(bucket));
        // loggingEnabled() should be null (no logging configured)
        assertThat(resp.loggingEnabled()).isNull();
    }

    @Test
    void putAndGetBucketLoggingRoundTrips() {
        // Use the same bucket as the log target (self-logging is allowed)
        s3.putBucketLogging(r -> r
                .bucket(bucket)
                .bucketLoggingStatus(ls -> ls
                        .loggingEnabled(le -> le
                                .targetBucket(bucket)
                                .targetPrefix("access-logs/"))));

        final GetBucketLoggingResponse resp =
                s3.getBucketLogging(r -> r.bucket(bucket));
        assertThat(resp.loggingEnabled()).isNotNull();
        assertThat(resp.loggingEnabled().targetBucket()).isEqualTo(bucket);
        assertThat(resp.loggingEnabled().targetPrefix()).isEqualTo("access-logs/");
    }

    @Test
    void putEmptyLoggingStatusDisablesLogging() {
        // Enable logging first
        s3.putBucketLogging(r -> r
                .bucket(bucket)
                .bucketLoggingStatus(ls -> ls
                        .loggingEnabled(le -> le
                                .targetBucket(bucket)
                                .targetPrefix("logs/"))));

        // Disable by sending empty status
        s3.putBucketLogging(r -> r
                .bucket(bucket)
                .bucketLoggingStatus(BucketLoggingStatus.builder().build()));  // no loggingEnabled block

        final GetBucketLoggingResponse resp =
                s3.getBucketLogging(r -> r.bucket(bucket));
        assertThat(resp.loggingEnabled()).isNull();
    }

    // -------------------------------------------------------------------------
    // Replication configuration
    // -------------------------------------------------------------------------

    @Test
    void getBucketReplicationThrows404WhenNoneSet() {
        assertThatThrownBy(() ->
                s3.getBucketReplication(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    @Test
    void putAndGetBucketReplicationRoundTrips() {
        final String destBucket = "arn:aws:s3:::destination-bucket";
        s3.putBucketReplication(r -> r
                .bucket(bucket)
                .replicationConfiguration(ReplicationConfiguration.builder()
                        .role("arn:aws:iam::123456789012:role/replication-role")
                        .rules(List.of(
                                ReplicationRule.builder()
                                        .id("rule-1")
                                        .status(ReplicationRuleStatus.ENABLED)
                                        .filter(ReplicationRuleFilter.fromPrefix(""))
                                        .destination(Destination.builder()
                                                .bucket(destBucket)
                                                .build())
                                        .build()))
                        .build()));

        final GetBucketReplicationResponse resp =
                s3.getBucketReplication(r -> r.bucket(bucket));
        assertThat(resp.replicationConfiguration()).isNotNull();
        assertThat(resp.replicationConfiguration().rules()).hasSize(1);
        assertThat(resp.replicationConfiguration().rules().get(0).id()).isEqualTo("rule-1");
        assertThat(resp.replicationConfiguration().rules().get(0).destination().bucket())
                .isEqualTo(destBucket);
    }

    @Test
    void deleteBucketReplicationClearsConfig() {
        // Set a replication config
        s3.putBucketReplication(r -> r
                .bucket(bucket)
                .replicationConfiguration(ReplicationConfiguration.builder()
                        .role("arn:aws:iam::123456789012:role/replication-role")
                        .rules(List.of(
                                ReplicationRule.builder()
                                        .id("rule-1")
                                        .status(ReplicationRuleStatus.ENABLED)
                                        .filter(ReplicationRuleFilter.fromPrefix(""))
                                        .destination(Destination.builder()
                                                .bucket("arn:aws:s3:::dest")
                                                .build())
                                        .build()))
                        .build()));

        // Then delete it
        s3.deleteBucketReplication(r -> r.bucket(bucket));

        // Verify it's gone (404)
        assertThatThrownBy(() ->
                s3.getBucketReplication(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    @Test
    void notificationAndLoggingOnNonExistentBucketThrows() {
        assertThatThrownBy(() ->
                s3.getBucketNotificationConfiguration(r -> r.bucket("nonexistent-bucket-xyz")))
                .isInstanceOf(NoSuchBucketException.class);
    }
}
