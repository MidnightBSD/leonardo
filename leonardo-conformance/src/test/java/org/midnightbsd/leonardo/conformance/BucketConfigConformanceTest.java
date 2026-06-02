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
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
import software.amazon.awssdk.services.s3.model.GetBucketCorsResponse;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketTaggingResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockResponse;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance tests for Phase 3 bucket configuration operations:
 * tagging, versioning, CORS, lifecycle rules, ACL, public-access block,
 * and bucket policy.
 */
final class BucketConfigConformanceTest extends ConformanceBase {

    // -------------------------------------------------------------------------
    // Bucket tagging
    // -------------------------------------------------------------------------

    @Test
    void bucketTaggingRoundTrip() {
        s3.putBucketTagging(r -> r.bucket(bucket).tagging(Tagging.builder()
                .tagSet(
                    Tag.builder().key("env").value("ci").build(),
                    Tag.builder().key("owner").value("conformance").build())
                .build()));

        final GetBucketTaggingResponse got = s3.getBucketTagging(r -> r.bucket(bucket));
        assertThat(got.tagSet()).hasSize(2);
        final var tagMap = got.tagSet().stream()
                .collect(java.util.stream.Collectors.toMap(Tag::key, Tag::value));
        assertThat(tagMap).containsEntry("env", "ci").containsEntry("owner", "conformance");

        s3.deleteBucketTagging(r -> r.bucket(bucket));
        // after deletion, getBucketTagging should return empty or throw NoSuchTagSet
        try {
            final GetBucketTaggingResponse empty = s3.getBucketTagging(r -> r.bucket(bucket));
            assertThat(empty.tagSet()).isEmpty();
        } catch (S3Exception ex) {
            assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("NoSuchTagSet");
        }
    }

    // -------------------------------------------------------------------------
    // Bucket versioning
    // -------------------------------------------------------------------------

    @Test
    void bucketVersioningEnableAndStatus() {
        final GetBucketVersioningResponse before = s3.getBucketVersioning(r -> r.bucket(bucket));
        // freshly created bucket: versioning not enabled
        assertThat(before.statusAsString()).satisfiesAnyOf(
                s -> assertThat(s).isNullOrEmpty(),
                s -> assertThat(s).isEqualTo("Disabled")
        );

        s3.putBucketVersioning(r -> r.bucket(bucket).versioningConfiguration(v ->
                v.status(BucketVersioningStatus.ENABLED)));

        final GetBucketVersioningResponse after = s3.getBucketVersioning(r -> r.bucket(bucket));
        assertThat(after.status()).isEqualTo(BucketVersioningStatus.ENABLED);
    }

    @Test
    void bucketVersioningSuspend() {
        s3.putBucketVersioning(r -> r.bucket(bucket).versioningConfiguration(v ->
                v.status(BucketVersioningStatus.ENABLED)));
        s3.putBucketVersioning(r -> r.bucket(bucket).versioningConfiguration(v ->
                v.status(BucketVersioningStatus.SUSPENDED)));

        final GetBucketVersioningResponse status = s3.getBucketVersioning(r -> r.bucket(bucket));
        assertThat(status.status()).isEqualTo(BucketVersioningStatus.SUSPENDED);
    }

    // -------------------------------------------------------------------------
    // CORS
    // -------------------------------------------------------------------------

    @Test
    void bucketCorsRoundTrip() {
        s3.putBucketCors(r -> r.bucket(bucket).corsConfiguration(c -> c.corsRules(
                CORSRule.builder()
                        .allowedMethods("GET", "PUT")
                        .allowedOrigins("https://example.com")
                        .allowedHeaders("*")
                        .maxAgeSeconds(3600)
                        .build())));

        final GetBucketCorsResponse got = s3.getBucketCors(r -> r.bucket(bucket));
        assertThat(got.corsRules()).hasSize(1);
        final CORSRule rule = got.corsRules().get(0);
        assertThat(rule.allowedMethods()).containsExactlyInAnyOrder("GET", "PUT");
        assertThat(rule.allowedOrigins()).containsExactly("https://example.com");

        s3.deleteBucketCors(r -> r.bucket(bucket));
        assertThatThrownBy(() -> s3.getBucketCors(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class);
    }

    // -------------------------------------------------------------------------
    // Lifecycle configuration
    // -------------------------------------------------------------------------

    @Test
    void bucketLifecycleRoundTrip() {
        s3.putBucketLifecycleConfiguration(r -> r.bucket(bucket).lifecycleConfiguration(c -> c.rules(
                LifecycleRule.builder()
                        .id("expire-tmp")
                        .status(ExpirationStatus.ENABLED)
                        .filter(f -> f.prefix("tmp/"))
                        .expiration(e -> e.days(7))
                        .build())));

        final GetBucketLifecycleConfigurationResponse got =
                s3.getBucketLifecycleConfiguration(r -> r.bucket(bucket));
        assertThat(got.rules()).hasSize(1);
        final LifecycleRule rule = got.rules().get(0);
        assertThat(rule.id()).isEqualTo("expire-tmp");
        assertThat(rule.status()).isEqualTo(ExpirationStatus.ENABLED);
        assertThat(rule.expiration().days()).isEqualTo(7);

        s3.deleteBucketLifecycle(r -> r.bucket(bucket));
        assertThatThrownBy(() -> s3.getBucketLifecycleConfiguration(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class);
    }

    // -------------------------------------------------------------------------
    // ACL
    // -------------------------------------------------------------------------

    @Test
    void getBucketAclReturnsOwnerGrant() {
        final GetBucketAclResponse acl = s3.getBucketAcl(r -> r.bucket(bucket));
        assertThat(acl.owner()).isNotNull();
        assertThat(acl.grants()).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Public access block
    // -------------------------------------------------------------------------

    @Test
    void publicAccessBlockRoundTrip() {
        s3.putPublicAccessBlock(r -> r.bucket(bucket).publicAccessBlockConfiguration(c -> c
                .blockPublicAcls(true)
                .ignorePublicAcls(true)
                .blockPublicPolicy(true)
                .restrictPublicBuckets(true)));

        final GetPublicAccessBlockResponse got =
                s3.getPublicAccessBlock(r -> r.bucket(bucket));
        assertThat(got.publicAccessBlockConfiguration().blockPublicAcls()).isTrue();
        assertThat(got.publicAccessBlockConfiguration().blockPublicPolicy()).isTrue();

        s3.deletePublicAccessBlock(r -> r.bucket(bucket));
        assertThatThrownBy(() -> s3.getPublicAccessBlock(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class);
    }

    // -------------------------------------------------------------------------
    // Bucket policy
    // -------------------------------------------------------------------------

    @Test
    void bucketPolicyRoundTrip() {
        final String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Sid": "AllowGet",
                    "Effect": "Allow",
                    "Principal": "*",
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::%s/*"
                  }]
                }""".formatted(bucket);

        s3.putBucketPolicy(r -> r.bucket(bucket).policy(policy));
        final String got = s3.getBucketPolicy(r -> r.bucket(bucket)).policy();
        // The stored policy may have whitespace differences; check key tokens
        assertThat(got).contains("AllowGet", "s3:GetObject");

        s3.deleteBucketPolicy(r -> r.bucket(bucket));
        assertThatThrownBy(() -> s3.getBucketPolicy(r -> r.bucket(bucket)))
                .isInstanceOf(S3Exception.class);
    }

    @Test
    void malformedBucketPolicyIsRejected() {
        assertThatThrownBy(() ->
                s3.putBucketPolicy(r -> r.bucket(bucket).policy("{not valid json")))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode())
                        .isBetween(400, 499));
    }

    @Test
    void bucketPolicyWithWrongVersionIsRejected() {
        final String badPolicy = """
                {
                  "Version": "2008-10-17",
                  "Statement": [{"Effect": "Allow", "Principal": "*",
                    "Action": "s3:GetObject",
                    "Resource": "arn:aws:s3:::%s/*"}]
                }""".formatted(bucket);
        assertThatThrownBy(() -> s3.putBucketPolicy(r -> r.bucket(bucket).policy(badPolicy)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode())
                        .isBetween(400, 499));
    }
}
