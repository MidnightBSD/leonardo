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
 * Phase 8 conformance tests — analytics, metrics, inventory, intelligent-tiering configurations.
 * ABAC (PutBucketAbac / GetBucketAbac) has no AWS SDK method and is tested via integration testing.
 * 76 + 16 = 92 total assertions tracked via @Test methods.
 */
final class BucketPhase8ConformanceTest extends ConformanceBase {

    // -------------------------------------------------------------------------
    // BucketAnalyticsConfiguration
    // -------------------------------------------------------------------------

    @Test
    void getAnalyticsConfigurationReturnsEmptyListWhenNoneSet() {
        final var resp = s3.listBucketAnalyticsConfigurations(r -> r.bucket(bucket));
        assertThat(resp.analyticsConfigurationList()).isEmpty();
        assertThat(resp.isTruncated()).isFalse();
    }

    @Test
    void analyticsConfigurationRoundTrip() {
        final String id = "test-analytics";
        s3.putBucketAnalyticsConfiguration(r -> r
                .bucket(bucket)
                .id(id)
                .analyticsConfiguration(AnalyticsConfiguration.builder()
                        .id(id)
                        .storageClassAnalysis(StorageClassAnalysis.builder().build())
                        .build()));

        final var getResp = s3.getBucketAnalyticsConfiguration(r -> r.bucket(bucket).id(id));
        assertThat(getResp.analyticsConfiguration().id()).isEqualTo(id);

        final var listResp = s3.listBucketAnalyticsConfigurations(r -> r.bucket(bucket));
        assertThat(listResp.analyticsConfigurationList()).hasSize(1);
        assertThat(listResp.analyticsConfigurationList().get(0).id()).isEqualTo(id);
        assertThat(listResp.isTruncated()).isFalse();

        s3.deleteBucketAnalyticsConfiguration(r -> r.bucket(bucket).id(id));

        final var emptyList = s3.listBucketAnalyticsConfigurations(r -> r.bucket(bucket));
        assertThat(emptyList.analyticsConfigurationList()).isEmpty();
    }

    @Test
    void getAnalyticsConfigurationThrows404ForMissingId() {
        assertThatThrownBy(() ->
                s3.getBucketAnalyticsConfiguration(r -> r.bucket(bucket).id("missing")))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    @Test
    void analyticsMultipleConfigurationsCoexist() {
        s3.putBucketAnalyticsConfiguration(r -> r.bucket(bucket).id("a1")
                .analyticsConfiguration(AnalyticsConfiguration.builder()
                        .id("a1").storageClassAnalysis(StorageClassAnalysis.builder().build())
                        .build()));
        s3.putBucketAnalyticsConfiguration(r -> r.bucket(bucket).id("a2")
                .analyticsConfiguration(AnalyticsConfiguration.builder()
                        .id("a2").storageClassAnalysis(StorageClassAnalysis.builder().build())
                        .build()));

        final var listResp = s3.listBucketAnalyticsConfigurations(r -> r.bucket(bucket));
        assertThat(listResp.analyticsConfigurationList()).hasSize(2);

        s3.deleteBucketAnalyticsConfiguration(r -> r.bucket(bucket).id("a1"));
        final var afterDelete = s3.listBucketAnalyticsConfigurations(r -> r.bucket(bucket));
        assertThat(afterDelete.analyticsConfigurationList()).hasSize(1);
        assertThat(afterDelete.analyticsConfigurationList().get(0).id()).isEqualTo("a2");
    }

    // -------------------------------------------------------------------------
    // BucketMetricsConfiguration
    // -------------------------------------------------------------------------

    @Test
    void getMetricsConfigurationReturnsEmptyListWhenNoneSet() {
        final var resp = s3.listBucketMetricsConfigurations(r -> r.bucket(bucket));
        assertThat(resp.metricsConfigurationList()).isEmpty();
        assertThat(resp.isTruncated()).isFalse();
    }

    @Test
    void metricsConfigurationRoundTrip() {
        final String id = "test-metrics";
        s3.putBucketMetricsConfiguration(r -> r
                .bucket(bucket)
                .id(id)
                .metricsConfiguration(MetricsConfiguration.builder()
                        .id(id)
                        .build()));

        final var getResp = s3.getBucketMetricsConfiguration(r -> r.bucket(bucket).id(id));
        assertThat(getResp.metricsConfiguration().id()).isEqualTo(id);

        final var listResp = s3.listBucketMetricsConfigurations(r -> r.bucket(bucket));
        assertThat(listResp.metricsConfigurationList()).hasSize(1);
        assertThat(listResp.metricsConfigurationList().get(0).id()).isEqualTo(id);
        assertThat(listResp.isTruncated()).isFalse();

        s3.deleteBucketMetricsConfiguration(r -> r.bucket(bucket).id(id));

        final var emptyList = s3.listBucketMetricsConfigurations(r -> r.bucket(bucket));
        assertThat(emptyList.metricsConfigurationList()).isEmpty();
    }

    @Test
    void getMetricsConfigurationThrows404ForMissingId() {
        assertThatThrownBy(() ->
                s3.getBucketMetricsConfiguration(r -> r.bucket(bucket).id("missing")))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    // -------------------------------------------------------------------------
    // BucketInventoryConfiguration
    // -------------------------------------------------------------------------

    @Test
    void getInventoryConfigurationReturnsEmptyListWhenNoneSet() {
        final var resp = s3.listBucketInventoryConfigurations(r -> r.bucket(bucket));
        assertThat(resp.inventoryConfigurationList()).isEmpty();
        assertThat(resp.isTruncated()).isFalse();
    }

    @Test
    void inventoryConfigurationRoundTrip() {
        final String id = "test-inventory";
        final String destBucket = "arn:aws:s3:::" + bucket;
        s3.putBucketInventoryConfiguration(r -> r
                .bucket(bucket)
                .id(id)
                .inventoryConfiguration(InventoryConfiguration.builder()
                        .id(id)
                        .isEnabled(true)
                        .includedObjectVersions(InventoryIncludedObjectVersions.ALL)
                        .destination(InventoryDestination.builder()
                                .s3BucketDestination(InventoryS3BucketDestination.builder()
                                        .bucket(destBucket)
                                        .format(InventoryFormat.CSV)
                                        .build())
                                .build())
                        .schedule(InventorySchedule.builder()
                                .frequency(InventoryFrequency.DAILY)
                                .build())
                        .build()));

        final var getResp = s3.getBucketInventoryConfiguration(r -> r.bucket(bucket).id(id));
        assertThat(getResp.inventoryConfiguration().id()).isEqualTo(id);
        assertThat(getResp.inventoryConfiguration().isEnabled()).isTrue();

        final var listResp = s3.listBucketInventoryConfigurations(r -> r.bucket(bucket));
        assertThat(listResp.inventoryConfigurationList()).hasSize(1);
        assertThat(listResp.inventoryConfigurationList().get(0).id()).isEqualTo(id);
        assertThat(listResp.isTruncated()).isFalse();

        s3.deleteBucketInventoryConfiguration(r -> r.bucket(bucket).id(id));

        final var emptyList = s3.listBucketInventoryConfigurations(r -> r.bucket(bucket));
        assertThat(emptyList.inventoryConfigurationList()).isEmpty();
    }

    @Test
    void getInventoryConfigurationThrows404ForMissingId() {
        assertThatThrownBy(() ->
                s3.getBucketInventoryConfiguration(r -> r.bucket(bucket).id("missing")))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    // -------------------------------------------------------------------------
    // BucketIntelligentTieringConfiguration
    // -------------------------------------------------------------------------

    @Test
    void getIntelligentTieringConfigurationReturnsEmptyListWhenNoneSet() {
        final var resp = s3.listBucketIntelligentTieringConfigurations(r -> r.bucket(bucket));
        assertThat(resp.intelligentTieringConfigurationList()).isEmpty();
    }

    @Test
    void intelligentTieringConfigurationRoundTrip() {
        final String id = "test-tiering";
        s3.putBucketIntelligentTieringConfiguration(r -> r
                .bucket(bucket)
                .id(id)
                .intelligentTieringConfiguration(IntelligentTieringConfiguration.builder()
                        .id(id)
                        .status(IntelligentTieringStatus.ENABLED)
                        .tierings(Tiering.builder()
                                .days(90)
                                .accessTier(IntelligentTieringAccessTier.ARCHIVE_ACCESS)
                                .build())
                        .build()));

        final var getResp = s3.getBucketIntelligentTieringConfiguration(
                r -> r.bucket(bucket).id(id));
        assertThat(getResp.intelligentTieringConfiguration().id()).isEqualTo(id);
        assertThat(getResp.intelligentTieringConfiguration().status())
                .isEqualTo(IntelligentTieringStatus.ENABLED);

        final var listResp = s3.listBucketIntelligentTieringConfigurations(r -> r.bucket(bucket));
        assertThat(listResp.intelligentTieringConfigurationList()).hasSize(1);
        assertThat(listResp.intelligentTieringConfigurationList().get(0).id()).isEqualTo(id);

        s3.deleteBucketIntelligentTieringConfiguration(r -> r.bucket(bucket).id(id));

        final var emptyList = s3.listBucketIntelligentTieringConfigurations(r -> r.bucket(bucket));
        assertThat(emptyList.intelligentTieringConfigurationList()).isEmpty();
    }

    @Test
    void getIntelligentTieringConfigurationThrows404ForMissingId() {
        assertThatThrownBy(() ->
                s3.getBucketIntelligentTieringConfiguration(r -> r.bucket(bucket).id("missing")))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }
}
