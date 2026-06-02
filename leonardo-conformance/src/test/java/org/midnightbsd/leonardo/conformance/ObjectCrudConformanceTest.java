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
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance tests for core object operations: PutObject, GetObject,
 * HeadObject, DeleteObject, DeleteObjects, CopyObject, ListObjects,
 * ListObjectsV2, range requests, user metadata, and error codes.
 */
final class ObjectCrudConformanceTest extends ConformanceBase {

    private static final String KEY    = "test-object.txt";
    private static final byte[] BODY   = "Hello, Leonardo!".getBytes();
    private static final String CTYPE  = "text/plain";

    // -------------------------------------------------------------------------
    // PutObject / GetObject round-trip
    // -------------------------------------------------------------------------

    @Test
    void putAndGetObjectRoundTrip() {
        s3.putObject(r -> r.bucket(bucket).key(KEY).contentType(CTYPE),
                RequestBody.fromBytes(BODY));

        final ResponseBytes<GetObjectResponse> got =
                s3.getObjectAsBytes(r -> r.bucket(bucket).key(KEY));

        assertThat(got.asByteArray()).isEqualTo(BODY);
        assertThat(got.response().contentType()).isEqualTo(CTYPE);
        assertThat(got.response().contentLength()).isEqualTo(BODY.length);
    }

    @Test
    void eTagIsMd5HexOfContent() throws NoSuchAlgorithmException {
        s3.putObject(r -> r.bucket(bucket).key(KEY), RequestBody.fromBytes(BODY));

        final HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key(KEY));
        final String expectedEtag = '"' + md5Hex(BODY) + '"';
        assertThat(head.eTag()).isEqualTo(expectedEtag);
    }

    @Test
    void headObjectReturnsCorrectMetadata() {
        s3.putObject(r -> r.bucket(bucket).key(KEY).contentType(CTYPE),
                RequestBody.fromBytes(BODY));

        final HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key(KEY));
        assertThat(head.contentLength()).isEqualTo(BODY.length);
        assertThat(head.contentType()).isEqualTo(CTYPE);
        assertThat(head.eTag()).isNotBlank();
        assertThat(head.lastModified()).isNotNull();
    }

    @Test
    void userDefinedMetadataRoundTrips() {
        final Map<String, String> meta = Map.of(
                "x-amz-meta-author", "conformance-test",
                "x-amz-meta-version", "1.0");
        s3.putObject(r -> r.bucket(bucket).key(KEY).metadata(meta),
                RequestBody.fromBytes(BODY));

        final HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key(KEY));
        assertThat(head.metadata()).containsAllEntriesOf(Map.of(
                "author", "conformance-test",
                "version", "1.0"));
    }

    // -------------------------------------------------------------------------
    // Range requests
    // -------------------------------------------------------------------------

    @Test
    void rangeRequestReturnsPartialContent() {
        final byte[] large = new byte[1024];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i & 0xFF);
        }
        s3.putObject(r -> r.bucket(bucket).key(KEY), RequestBody.fromBytes(large));

        final ResponseBytes<GetObjectResponse> partial = s3.getObjectAsBytes(r ->
                r.bucket(bucket).key(KEY).range("bytes=0-99"));

        assertThat(partial.response().sdkHttpResponse().statusCode()).isEqualTo(206);
        assertThat(partial.asByteArray()).hasSize(100);
        assertThat(partial.response().contentRange()).isEqualTo("bytes 0-99/1024");
    }

    @Test
    void suffixRangeRequestReturnsLastNBytes() {
        final byte[] data = new byte[512];
        for (int i = 0; i < 512; i++) {
            data[i] = (byte) i;
        }
        s3.putObject(r -> r.bucket(bucket).key(KEY), RequestBody.fromBytes(data));

        final ResponseBytes<GetObjectResponse> partial = s3.getObjectAsBytes(r ->
                r.bucket(bucket).key(KEY).range("bytes=-128"));

        assertThat(partial.asByteArray()).hasSize(128);
        // last byte should match the last byte of our data array
        final byte[] got = partial.asByteArray();
        assertThat(got[127]).isEqualTo(data[511]);
    }

    // -------------------------------------------------------------------------
    // DeleteObject
    // -------------------------------------------------------------------------

    @Test
    void deleteObjectMakesItInaccessible() {
        s3.putObject(r -> r.bucket(bucket).key(KEY), RequestBody.fromBytes(BODY));
        s3.deleteObject(r -> r.bucket(bucket).key(KEY));

        assertThatThrownBy(() -> s3.headObject(r -> r.bucket(bucket).key(KEY)))
                .isInstanceOf(NoSuchKeyException.class);
    }

    // -------------------------------------------------------------------------
    // DeleteObjects (batch)
    // -------------------------------------------------------------------------

    @Test
    void deleteObjectsBatchDeletesAll() {
        s3.putObject(r -> r.bucket(bucket).key("a.txt"), RequestBody.fromBytes(BODY));
        s3.putObject(r -> r.bucket(bucket).key("b.txt"), RequestBody.fromBytes(BODY));
        s3.putObject(r -> r.bucket(bucket).key("c.txt"), RequestBody.fromBytes(BODY));

        final DeleteObjectsResponse result = s3.deleteObjects(r -> r.bucket(bucket).delete(d -> d
                .objects(
                    ObjectIdentifier.builder().key("a.txt").build(),
                    ObjectIdentifier.builder().key("b.txt").build(),
                    ObjectIdentifier.builder().key("c.txt").build()
                ).quiet(false)));

        assertThat(result.deleted()).hasSize(3);
        assertThat(result.errors()).isEmpty();

        final ListObjectsV2Response list = s3.listObjectsV2(r -> r.bucket(bucket));
        assertThat(list.contents()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // CopyObject
    // -------------------------------------------------------------------------

    @Test
    void copyObjectCreatesDestAndKeepsSource() {
        final String dest = "dest-object.txt";
        s3.putObject(r -> r.bucket(bucket).key(KEY).contentType(CTYPE),
                RequestBody.fromBytes(BODY));

        final CopyObjectResponse copy = s3.copyObject(r -> r
                .sourceBucket(bucket).sourceKey(KEY)
                .destinationBucket(bucket).destinationKey(dest));
        assertThat(copy.sdkHttpResponse().statusCode()).isEqualTo(200);

        // both source and dest should be readable
        final byte[] srcData = s3.getObjectAsBytes(r -> r.bucket(bucket).key(KEY)).asByteArray();
        final byte[] dstData = s3.getObjectAsBytes(r -> r.bucket(bucket).key(dest)).asByteArray();
        assertThat(srcData).isEqualTo(BODY);
        assertThat(dstData).isEqualTo(BODY);
    }

    // -------------------------------------------------------------------------
    // ListObjectsV2
    // -------------------------------------------------------------------------

    @Test
    void listObjectsV2ReturnsPutObjects() {
        s3.putObject(r -> r.bucket(bucket).key("alpha.txt"), RequestBody.fromBytes(BODY));
        s3.putObject(r -> r.bucket(bucket).key("beta.txt"),  RequestBody.fromBytes(BODY));

        final ListObjectsV2Response list = s3.listObjectsV2(r -> r.bucket(bucket));
        final List<String> keys = list.contents().stream().map(S3Object::key).toList();
        assertThat(keys).containsExactlyInAnyOrder("alpha.txt", "beta.txt");
    }

    @Test
    void listObjectsV2WithPrefixFilters() {
        s3.putObject(r -> r.bucket(bucket).key("logs/2026/jan.log"),  RequestBody.fromBytes(BODY));
        s3.putObject(r -> r.bucket(bucket).key("logs/2026/feb.log"),  RequestBody.fromBytes(BODY));
        s3.putObject(r -> r.bucket(bucket).key("data/record.json"),   RequestBody.fromBytes(BODY));

        final ListObjectsV2Response result = s3.listObjectsV2(r ->
                r.bucket(bucket).prefix("logs/"));

        assertThat(result.contents()).hasSize(2);
        assertThat(result.contents()).allMatch(o -> o.key().startsWith("logs/"));
    }

    @Test
    void listObjectsV2WithDelimiterProducesCommonPrefixes() {
        s3.putObject(r -> r.bucket(bucket).key("a/x.txt"), RequestBody.fromBytes(BODY));
        s3.putObject(r -> r.bucket(bucket).key("a/y.txt"), RequestBody.fromBytes(BODY));
        s3.putObject(r -> r.bucket(bucket).key("b/z.txt"), RequestBody.fromBytes(BODY));

        final ListObjectsV2Response result = s3.listObjectsV2(r ->
                r.bucket(bucket).delimiter("/"));

        assertThat(result.commonPrefixes()).hasSize(2);
        final List<String> prefixes = result.commonPrefixes().stream()
                .map(cp -> cp.prefix())
                .toList();
        assertThat(prefixes).containsExactlyInAnyOrder("a/", "b/");
    }

    @Test
    void listObjectsV2PaginatesWithMaxKeys() {
        for (int i = 0; i < 5; i++) {
            s3.putObject(r -> r.bucket(bucket).key("item-" + String.format("%03d", 0)),
                    RequestBody.fromBytes(BODY));
        }
        // Need non-shadowed loop variable for lambda
        for (int i = 1; i < 5; i++) {
            final int n = i;
            s3.putObject(r -> r.bucket(bucket).key("item-" + String.format("%03d", n)),
                    RequestBody.fromBytes(BODY));
        }

        final ListObjectsV2Response page1 = s3.listObjectsV2(r ->
                r.bucket(bucket).maxKeys(3));
        assertThat(page1.contents()).hasSize(3);
        assertThat(page1.isTruncated()).isTrue();
        assertThat(page1.nextContinuationToken()).isNotBlank();

        final String token = page1.nextContinuationToken();
        final ListObjectsV2Response page2 = s3.listObjectsV2(r ->
                r.bucket(bucket).maxKeys(3).continuationToken(token));
        assertThat(page2.contents()).hasSizeBetween(1, 3);
    }

    // -------------------------------------------------------------------------
    // ListObjects (v1 legacy API)
    // -------------------------------------------------------------------------

    @Test
    void listObjectsV1ReturnsObjects() {
        s3.putObject(r -> r.bucket(bucket).key("file1.dat"), RequestBody.fromBytes(BODY));
        s3.putObject(r -> r.bucket(bucket).key("file2.dat"), RequestBody.fromBytes(BODY));

        final ListObjectsResponse list = s3.listObjects(r -> r.bucket(bucket));
        assertThat(list.contents()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Error codes
    // -------------------------------------------------------------------------

    @Test
    void getObjectFromNonExistentKeyThrowsNoSuchKey() {
        assertThatThrownBy(() -> s3.getObjectAsBytes(r -> r.bucket(bucket).key("missing.txt")))
                .isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void getObjectFromNonExistentBucketThrowsNoSuchBucket() {
        assertThatThrownBy(() -> s3.getObjectAsBytes(r ->
                r.bucket("cf-definitely-missing-bucket").key("k")))
                .isInstanceOf(NoSuchBucketException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String md5Hex(final byte[] data) {
        try {
            final MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
