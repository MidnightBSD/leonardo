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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectAclResponse;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesResponse;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.ObjectAttributes;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance tests for Phase 4 object configuration: object tagging,
 * object ACL, and GetObjectAttributes.
 */
final class ObjectConfigConformanceTest extends ConformanceBase {

    private static final String KEY  = "config-test-object";
    private static final byte[] BODY = "object config payload".getBytes();

    @BeforeEach
    void putTestObject() {
        s3.putObject(r -> r.bucket(bucket).key(KEY).contentType("text/plain"),
                RequestBody.fromBytes(BODY));
    }

    // -------------------------------------------------------------------------
    // Object tagging
    // -------------------------------------------------------------------------

    @Test
    void objectTaggingRoundTrip() {
        s3.putObjectTagging(r -> r.bucket(bucket).key(KEY).tagging(Tagging.builder()
                .tagSet(
                    Tag.builder().key("project").value("leonardo").build(),
                    Tag.builder().key("stage").value("ci").build())
                .build()));

        final GetObjectTaggingResponse got = s3.getObjectTagging(r -> r.bucket(bucket).key(KEY));
        assertThat(got.tagSet()).hasSize(2);
        final var tagMap = got.tagSet().stream()
                .collect(java.util.stream.Collectors.toMap(Tag::key, Tag::value));
        assertThat(tagMap).containsEntry("project", "leonardo").containsEntry("stage", "ci");

        s3.deleteObjectTagging(r -> r.bucket(bucket).key(KEY));

        final GetObjectTaggingResponse empty = s3.getObjectTagging(r -> r.bucket(bucket).key(KEY));
        assertThat(empty.tagSet()).isEmpty();
    }

    @Test
    void overwriteObjectTagsReplacesAll() {
        s3.putObjectTagging(r -> r.bucket(bucket).key(KEY).tagging(Tagging.builder()
                .tagSet(Tag.builder().key("first").value("yes").build())
                .build()));
        s3.putObjectTagging(r -> r.bucket(bucket).key(KEY).tagging(Tagging.builder()
                .tagSet(Tag.builder().key("second").value("yes").build())
                .build()));

        final GetObjectTaggingResponse got = s3.getObjectTagging(r -> r.bucket(bucket).key(KEY));
        assertThat(got.tagSet()).hasSize(1);
        assertThat(got.tagSet().get(0).key()).isEqualTo("second");
    }

    // -------------------------------------------------------------------------
    // Object ACL
    // -------------------------------------------------------------------------

    @Test
    void getObjectAclReturnsOwnerGrant() {
        final GetObjectAclResponse acl = s3.getObjectAcl(r -> r.bucket(bucket).key(KEY));
        assertThat(acl.owner()).isNotNull();
        assertThat(acl.grants()).isNotEmpty();
    }

    @Test
    void putObjectAclPrivateSucceeds() {
        // Canned ACL 'private' is the default; re-applying it should succeed
        s3.putObjectAcl(r -> r.bucket(bucket).key(KEY).acl(
                software.amazon.awssdk.services.s3.model.ObjectCannedACL.PRIVATE));
        final GetObjectAclResponse acl = s3.getObjectAcl(r -> r.bucket(bucket).key(KEY));
        assertThat(acl.grants()).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // GetObjectAttributes
    // -------------------------------------------------------------------------

    @Test
    void getObjectAttributesReturnsStorageClassAndSize() {
        final GetObjectAttributesResponse attrs = s3.getObjectAttributes(r -> r
                .bucket(bucket).key(KEY)
                .objectAttributes(ObjectAttributes.STORAGE_CLASS, ObjectAttributes.OBJECT_SIZE));

        assertThat(attrs.storageClass()).isNotNull();
        assertThat(attrs.objectSize()).isEqualTo(BODY.length);
    }

    @Test
    void getObjectAttributesReturnsETagWhenRequested() {
        final GetObjectAttributesResponse attrs = s3.getObjectAttributes(r -> r
                .bucket(bucket).key(KEY)
                .objectAttributes(ObjectAttributes.E_TAG));

        assertThat(attrs.eTag()).isNotBlank();
    }
}
