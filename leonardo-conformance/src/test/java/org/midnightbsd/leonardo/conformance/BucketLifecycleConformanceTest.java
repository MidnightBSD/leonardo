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
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance tests for bucket lifecycle: CreateBucket, HeadBucket,
 * GetBucketLocation, ListBuckets, and DeleteBucket.
 */
final class BucketLifecycleConformanceTest extends ConformanceBase {

    @Test
    void headBucketReturns200ForExistingBucket() {
        final HeadBucketResponse r = s3.headBucket(b -> b.bucket(bucket));
        assertThat(r.sdkHttpResponse().statusCode()).isEqualTo(200);
    }

    @Test
    void createDuplicateBucketThrows() {
        assertThatThrownBy(() -> s3.createBucket(b -> b.bucket(bucket)))
                .isInstanceOf(BucketAlreadyOwnedByYouException.class);
    }

    @Test
    void deleteBucketMakesHeadReturn404() {
        s3.deleteBucket(b -> b.bucket(bucket));
        assertThatThrownBy(() -> s3.headBucket(b -> b.bucket(bucket)))
                .isInstanceOf(NoSuchBucketException.class);
        bucket = "__already_deleted__";  // prevent @AfterEach double-delete
    }

    @Test
    void listBucketsIncludesCreatedBucket() {
        final ListBucketsResponse response = s3.listBuckets();
        final List<String> names = response.buckets().stream()
                .map(Bucket::name)
                .toList();
        assertThat(names).contains(bucket);
    }

    @Test
    void getBucketLocationReturnsConfiguredRegion() {
        final GetBucketLocationResponse loc = s3.getBucketLocation(r -> r.bucket(bucket));
        // AWS S3 returns null/empty for us-east-1; Leonardo may return "us-east-1"
        final String constraint = loc.locationConstraintAsString();
        assertThat(constraint).satisfiesAnyOf(
                c -> assertThat(c).isNullOrEmpty(),
                c -> assertThat(c).isEqualTo("us-east-1")
        );
    }

    @Test
    void headNonExistentBucketThrowsNoSuchBucket() {
        assertThatThrownBy(() -> s3.headBucket(b -> b.bucket("cf-does-not-exist-000001")))
                .isInstanceOf(NoSuchBucketException.class);
    }

    @Test
    void deleteNonExistentBucketThrowsNoSuchBucket() {
        assertThatThrownBy(() -> s3.deleteBucket(b -> b.bucket("cf-does-not-exist-000001")))
                .isInstanceOf(NoSuchBucketException.class);
    }
}
