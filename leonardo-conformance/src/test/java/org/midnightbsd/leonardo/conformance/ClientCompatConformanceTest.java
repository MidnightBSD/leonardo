/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.conformance;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.TaggingDirective;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviours that real clients (boto3, mc, the AWS CLI) depend on and that a
 * 2026-10-31 smoke test found missing. Each test pins one of those fixes.
 */
final class ClientCompatConformanceTest extends ConformanceBase {

    /** 110 KiB — just above the 100 KiB min part size configured for the test env. */
    private static final int PART_SIZE = 110 * 1024;

    // -------------------------------------------------------------------------
    // Listings and version ids
    // -------------------------------------------------------------------------

    @Test
    void listObjectsHidesKeysWhoseLatestVersionIsADeleteMarker() {
        enableVersioning();
        s3.putObject(r -> r.bucket(bucket).key("doomed"), RequestBody.fromString("x"));
        s3.putObject(r -> r.bucket(bucket).key("kept"), RequestBody.fromString("y"));

        final DeleteObjectResponse del = s3.deleteObject(r -> r.bucket(bucket).key("doomed"));
        assertThat(del.deleteMarker()).isTrue();

        final ListObjectsV2Response v2 = s3.listObjectsV2(r -> r.bucket(bucket));
        assertThat(v2.contents()).extracting(S3Object::key).containsExactly("kept");
        assertThat(v2.keyCount()).isEqualTo(1);
        assertThat(s3.listObjects(r -> r.bucket(bucket)).contents())
                .extracting(S3Object::key).containsExactly("kept");

        final ListObjectVersionsResponse versions = s3.listObjectVersions(r -> r.bucket(bucket));
        assertThat(versions.deleteMarkers()).hasSize(1);
        assertThat(versions.deleteMarkers().get(0).key()).isEqualTo("doomed");
        assertThat(versions.versions()).extracting(ObjectVersion::key).containsExactly("doomed", "kept");
    }

    @Test
    void unversionedObjectsReportTheLiteralNullVersionIdAndCanBeDeletedWithIt() {
        s3.putObject(r -> r.bucket(bucket).key("plain"), RequestBody.fromString("x"));

        final ListObjectVersionsResponse versions = s3.listObjectVersions(r -> r.bucket(bucket));
        assertThat(versions.versions()).hasSize(1);
        assertThat(versions.versions().get(0).versionId()).isEqualTo("null");

        // `aws s3 rb --force` and `mc rm --versions` delete by the listed version id.
        assertThat(s3.headObject(r -> r.bucket(bucket).key("plain").versionId("null")).contentLength())
                .isEqualTo(1L);
        s3.deleteObject(r -> r.bucket(bucket).key("plain").versionId("null"));
        assertThatThrownBy(() -> s3.headObject(r -> r.bucket(bucket).key("plain")))
                .isInstanceOf(S3Exception.class);
    }

    @Test
    void deletingASpecificVersionEchoesTheVersionIdAndDeleteMarkerFlag() {
        enableVersioning();
        final PutObjectResponse put = s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("x"));
        final DeleteObjectResponse marker = s3.deleteObject(r -> r.bucket(bucket).key("k"));
        assertThat(marker.versionId()).isNotBlank();

        final DeleteObjectResponse removedMarker = s3.deleteObject(
                r -> r.bucket(bucket).key("k").versionId(marker.versionId()));
        assertThat(removedMarker.versionId()).isEqualTo(marker.versionId());
        assertThat(removedMarker.deleteMarker()).isTrue();

        final DeleteObjectResponse removedVersion = s3.deleteObject(
                r -> r.bucket(bucket).key("k").versionId(put.versionId()));
        assertThat(removedVersion.versionId()).isEqualTo(put.versionId());
        assertThat(Boolean.TRUE.equals(removedVersion.deleteMarker())).isFalse();
    }

    @Test
    void listObjectsV2IncludesOwnerOnlyWhenFetchOwnerIsSet() {
        s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("x"));

        assertThat(s3.listObjectsV2(r -> r.bucket(bucket)).contents().get(0).owner()).isNull();
        assertThat(s3.listObjectsV2(r -> r.bucket(bucket).fetchOwner(true)).contents().get(0).owner())
                .isNotNull();
        // v1 always carries the owner
        assertThat(s3.listObjects(r -> r.bucket(bucket)).contents().get(0).owner()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Keys and GET overrides
    // -------------------------------------------------------------------------

    @Test
    void keysWithATrailingSlashKeepIt() {
        s3.putObject(r -> r.bucket(bucket).key("folder/"), RequestBody.fromString(""));
        s3.putObject(r -> r.bucket(bucket).key("folder/file.txt"), RequestBody.fromString("f"));

        assertThat(s3.listObjectsV2(r -> r.bucket(bucket)).contents())
                .extracting(S3Object::key).containsExactly("folder/", "folder/file.txt");
        assertThat(s3.headObject(r -> r.bucket(bucket).key("folder/")).contentLength()).isZero();
        assertThatThrownBy(() -> s3.headObject(r -> r.bucket(bucket).key("folder")))
                .isInstanceOf(S3Exception.class);

        s3.deleteObject(r -> r.bucket(bucket).key("folder/"));
        assertThat(s3.listObjectsV2(r -> r.bucket(bucket)).contents())
                .extracting(S3Object::key).containsExactly("folder/file.txt");
    }

    @Test
    void getObjectHonoursResponseHeaderOverrides() throws IOException {
        s3.putObject(r -> r.bucket(bucket).key("k").contentType("text/plain"),
                RequestBody.fromString("hello"));

        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(r -> r.bucket(bucket).key("k")
                .responseContentType("application/octet-stream")
                .responseContentDisposition("attachment; filename=\"k.bin\"")
                .responseCacheControl("no-store"))) {
            assertThat(in.response().contentType()).isEqualTo("application/octet-stream");
            assertThat(in.response().contentDisposition()).isEqualTo("attachment; filename=\"k.bin\"");
            assertThat(in.response().cacheControl()).isEqualTo("no-store");
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        // the stored object is untouched
        assertThat(s3.headObject(r -> r.bucket(bucket).key("k")).contentType()).isEqualTo("text/plain");
    }

    @Test
    void unsatisfiableRangeReturnsInvalidRangeWithAnXmlBody() {
        s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("hello"));
        assertThatThrownBy(() -> s3.getObjectAsBytes(r -> r.bucket(bucket).key("k").range("bytes=100-200")))
                .isInstanceOfSatisfying(S3Exception.class, ex -> {
                    assertThat(ex.statusCode()).isEqualTo(416);
                    assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("InvalidRange");
                });
    }

    @Test
    void getBucketTaggingOnAnUntaggedBucketIsNoSuchTagSet() {
        assertThatThrownBy(() -> s3.getBucketTagging(r -> r.bucket(bucket)))
                .isInstanceOfSatisfying(S3Exception.class, ex -> {
                    assertThat(ex.statusCode()).isEqualTo(404);
                    assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("NoSuchTagSet");
                });
    }

    // -------------------------------------------------------------------------
    // CopyObject
    // -------------------------------------------------------------------------

    @Test
    void copyObjectReplaceDirectiveReplacesMetadataContentTypeAndTags() {
        s3.putObject(r -> r.bucket(bucket).key("src").contentType("text/plain")
                        .metadata(Map.of("origin", "one"))
                        .tagging(Tagging.builder().tagSet(Tag.builder().key("t").value("1").build()).build()),
                RequestBody.fromString("payload"));

        // default directive: COPY
        s3.copyObject(r -> r.sourceBucket(bucket).sourceKey("src").destinationBucket(bucket).destinationKey("copy"));
        HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key("copy"));
        assertThat(head.contentType()).isEqualTo("text/plain");
        assertThat(head.metadata()).containsEntry("origin", "one");

        // REPLACE
        s3.copyObject(r -> r.sourceBucket(bucket).sourceKey("src").destinationBucket(bucket).destinationKey("replaced")
                .metadataDirective(MetadataDirective.REPLACE)
                .contentType("application/json")
                .metadata(Map.of("origin", "two"))
                .taggingDirective(TaggingDirective.REPLACE)
                .tagging(Tagging.builder().tagSet(Tag.builder().key("t").value("2").build()).build()));
        head = s3.headObject(r -> r.bucket(bucket).key("replaced"));
        assertThat(head.contentType()).isEqualTo("application/json");
        assertThat(head.metadata()).containsExactlyEntriesOf(Map.of("origin", "two"));
        assertThat(s3.getObjectTagging(r -> r.bucket(bucket).key("replaced")).tagSet())
                .extracting(Tag::value).containsExactly("2");
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key("replaced")).asUtf8String()).isEqualTo("payload");
    }

    @Test
    void copyObjectOntoItselfRequiresReplace() {
        s3.putObject(r -> r.bucket(bucket).key("k").metadata(Map.of("a", "1")), RequestBody.fromString("x"));

        assertThatThrownBy(() -> s3.copyObject(r -> r.sourceBucket(bucket).sourceKey("k")
                .destinationBucket(bucket).destinationKey("k")))
                .isInstanceOfSatisfying(S3Exception.class,
                        ex -> assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("InvalidRequest"));

        // the idiom every CLI uses to rewrite metadata in place
        s3.copyObject(r -> r.sourceBucket(bucket).sourceKey("k").destinationBucket(bucket).destinationKey("k")
                .metadataDirective(MetadataDirective.REPLACE).metadata(Map.of("a", "2")));
        assertThat(s3.headObject(r -> r.bucket(bucket).key("k")).metadata()).containsEntry("a", "2");
    }

    @Test
    void copyObjectCanNameASourceVersion() {
        enableVersioning();
        final PutObjectResponse first = s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("one"));
        s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("two"));

        final CopyObjectResponse copy = s3.copyObject(r -> r.sourceBucket(bucket).sourceKey("k")
                .sourceVersionId(first.versionId()).destinationBucket(bucket).destinationKey("old"));
        assertThat(copy.copySourceVersionId()).isEqualTo(first.versionId());
        assertThat(copy.versionId()).isNotBlank();
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key("old")).asUtf8String()).isEqualTo("one");

        assertThatThrownBy(() -> s3.copyObject(r -> r.sourceBucket(bucket).sourceKey("k")
                .sourceVersionId("01HXDOESNOTEXIST").destinationBucket(bucket).destinationKey("nope")))
                .isInstanceOfSatisfying(S3Exception.class,
                        ex -> assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("NoSuchVersion"));
    }

    // -------------------------------------------------------------------------
    // Suspended versioning and overwrite housekeeping
    // -------------------------------------------------------------------------

    @Test
    void suspendedBucketKeepsRealVersionsAndHoldsASingleNullVersion() {
        enableVersioning();
        final String v1 = s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("one")).versionId();
        final String v2 = s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("two")).versionId();
        s3.putBucketVersioning(r -> r.bucket(bucket).versioningConfiguration(
                VersioningConfiguration.builder().status(BucketVersioningStatus.SUSPENDED).build()));

        final PutObjectResponse nullPut = s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("null-1"));
        assertThat(nullPut.versionId() == null || "null".equals(nullPut.versionId())).isTrue();
        s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("null-2"));

        ListObjectVersionsResponse versions = s3.listObjectVersions(r -> r.bucket(bucket));
        assertThat(versions.versions()).extracting(ObjectVersion::versionId).containsExactly("null", v2, v1);
        assertThat(versions.versions().get(0).isLatest()).isTrue();
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key("k")).asUtf8String()).isEqualTo("null-2");
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key("k").versionId(v1)).asUtf8String()).isEqualTo("one");

        // DELETE without a version id inserts a null delete marker over the null version
        final DeleteObjectResponse del = s3.deleteObject(r -> r.bucket(bucket).key("k"));
        assertThat(del.deleteMarker()).isTrue();
        assertThat(del.versionId()).isEqualTo("null");
        versions = s3.listObjectVersions(r -> r.bucket(bucket));
        assertThat(versions.versions()).extracting(ObjectVersion::versionId).containsExactly(v2, v1);
        assertThat(versions.deleteMarkers()).hasSize(1);
        assertThat(versions.deleteMarkers().get(0).versionId()).isEqualTo("null");
        assertThatThrownBy(() -> s3.headObject(r -> r.bucket(bucket).key("k"))).isInstanceOf(S3Exception.class);

        // removing the null marker brings v2 back
        s3.deleteObject(r -> r.bucket(bucket).key("k").versionId("null"));
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key("k")).asUtf8String()).isEqualTo("two");
    }

    @Test
    void deleteObjectsHonoursVersionIdsInsteadOfStackingMarkers() {
        enableVersioning();
        final String v1 = s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("one")).versionId();
        final String v2 = s3.putObject(r -> r.bucket(bucket).key("k"), RequestBody.fromString("two")).versionId();
        final String marker = s3.deleteObject(r -> r.bucket(bucket).key("k")).versionId();

        // `mc rm --versions`, `mc undo` and `aws s3 rb --force` all delete through DeleteObjects
        final DeleteObjectsResponse res = s3.deleteObjects(r -> r.bucket(bucket).delete(Delete.builder().objects(
                ObjectIdentifier.builder().key("k").versionId(marker).build(),
                ObjectIdentifier.builder().key("k").versionId(v2).build(),
                ObjectIdentifier.builder().key("k").versionId("01HXDOESNOTEXIST").build(),
                ObjectIdentifier.builder().key("other").build()).quiet(false).build()));

        assertThat(res.deleted()).extracting(DeletedObject::key).containsExactly("k", "k", "other");
        assertThat(res.deleted().get(0).versionId()).isEqualTo(marker);
        assertThat(res.deleted().get(0).deleteMarker()).isTrue();
        assertThat(res.deleted().get(1).versionId()).isEqualTo(v2);
        assertThat(res.deleted().get(2).deleteMarker()).isTrue();       // a new marker for "other"
        assertThat(res.deleted().get(2).deleteMarkerVersionId()).isNotBlank();
        assertThat(res.errors()).hasSize(1);
        assertThat(res.errors().get(0).versionId()).isEqualTo("01HXDOESNOTEXIST");
        assertThat(res.errors().get(0).code()).isEqualTo("NoSuchVersion");

        final ListObjectVersionsResponse versions = s3.listObjectVersions(r -> r.bucket(bucket));
        assertThat(versions.versions()).extracting(ObjectVersion::versionId).containsExactly(v1);
        assertThat(versions.deleteMarkers()).extracting(DeleteMarkerEntry::key).containsExactly("other");
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key("k")).asUtf8String()).isEqualTo("one");
    }

    @Test
    void bucketCanBeDeletedAfterOverwritesAndEmptying() {
        for (int i = 0; i < 5; i++) {
            final int n = i;
            s3.putObject(r -> r.bucket(bucket).key("same"), RequestBody.fromString("write " + n));
        }
        s3.putObject(r -> r.bucket(bucket).key("other"), RequestBody.fromString("x"));
        s3.copyObject(r -> r.sourceBucket(bucket).sourceKey("other").destinationBucket(bucket).destinationKey("other")
                .metadataDirective(MetadataDirective.REPLACE).metadata(Map.of("rewritten", "yes")));

        // empty it the way `aws s3 rb --force` does and delete it
        final ListObjectVersionsResponse versions = s3.listObjectVersions(r -> r.bucket(bucket));
        for (final ObjectVersion v : versions.versions()) {
            s3.deleteObject(r -> r.bucket(bucket).key(v.key()).versionId(v.versionId()));
        }
        s3.deleteBucket(r -> r.bucket(bucket));
        assertThatThrownBy(() -> s3.headBucket(r -> r.bucket(bucket))).isInstanceOf(S3Exception.class);
        s3.createBucket(r -> r.bucket(bucket));   // recreate so the base-class cleanup has something to remove
    }

    // -------------------------------------------------------------------------
    // Multipart
    // -------------------------------------------------------------------------

    @Test
    void uploadPartCopyHonoursTheSourceRange() {
        final byte[] src = new byte[3 * PART_SIZE];
        for (int i = 0; i < src.length; i++) src[i] = (byte) (i % 251);
        s3.putObject(r -> r.bucket(bucket).key("src"), RequestBody.fromBytes(src));

        final String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key("dst")).uploadId();
        final String etag1 = s3.uploadPartCopy(r -> r.sourceBucket(bucket).sourceKey("src")
                .destinationBucket(bucket).destinationKey("dst").uploadId(uploadId).partNumber(1)
                .copySourceRange("bytes=0-" + (PART_SIZE - 1))).copyPartResult().eTag();
        final String etag2 = s3.uploadPartCopy(r -> r.sourceBucket(bucket).sourceKey("src")
                .destinationBucket(bucket).destinationKey("dst").uploadId(uploadId).partNumber(2)
                .copySourceRange("bytes=" + PART_SIZE + "-" + (2 * PART_SIZE - 1))).copyPartResult().eTag();

        final ListPartsResponse parts = s3.listParts(r -> r.bucket(bucket).key("dst").uploadId(uploadId));
        assertThat(parts.parts()).allSatisfy(p -> assertThat(p.size()).isEqualTo((long) PART_SIZE));

        assertThatThrownBy(() -> s3.uploadPartCopy(r -> r.sourceBucket(bucket).sourceKey("src")
                .destinationBucket(bucket).destinationKey("dst").uploadId(uploadId).partNumber(3)
                .copySourceRange("bytes=0-" + (10 * PART_SIZE))))
                .isInstanceOfSatisfying(S3Exception.class,
                        ex -> assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("InvalidArgument"));

        s3.completeMultipartUpload(r -> r.bucket(bucket).key("dst").uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(
                        CompletedPart.builder().partNumber(1).eTag(etag1).build(),
                        CompletedPart.builder().partNumber(2).eTag(etag2).build()).build()));
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key("dst")).asByteArray())
                .isEqualTo(Arrays.copyOfRange(src, 0, 2 * PART_SIZE));
    }

    @Test
    void completeMultipartUploadRejectsWrongEtagsAndKeepsTheUploadAlive() {
        final String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key("k")).uploadId();
        final String etag = s3.uploadPart(r -> r.bucket(bucket).key("k").uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(new byte[PART_SIZE])).eTag();

        assertThatThrownBy(() -> s3.completeMultipartUpload(r -> r.bucket(bucket).key("k").uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(
                        CompletedPart.builder().partNumber(1).eTag("\"00000000000000000000000000000000\"").build())
                        .build())))
                .isInstanceOfSatisfying(S3Exception.class,
                        ex -> assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("InvalidPart"));
        assertThatThrownBy(() -> s3.completeMultipartUpload(r -> r.bucket(bucket).key("k").uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(
                        CompletedPart.builder().partNumber(7).eTag(etag).build()).build())))
                .isInstanceOfSatisfying(S3Exception.class,
                        ex -> assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("InvalidPart"));

        // The upload survived both rejections: it can still be listed, completed or aborted.
        assertThat(s3.listParts(r -> r.bucket(bucket).key("k").uploadId(uploadId)).parts()).hasSize(1);
        assertThatThrownBy(() -> s3.headObject(r -> r.bucket(bucket).key("k"))).isInstanceOf(S3Exception.class);
        s3.completeMultipartUpload(r -> r.bucket(bucket).key("k").uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(
                        CompletedPart.builder().partNumber(1).eTag(etag).build()).build()));
        assertThat(s3.headObject(r -> r.bucket(bucket).key("k")).contentLength()).isEqualTo((long) PART_SIZE);
    }

    @Test
    void completeMultipartUploadRejectsSmallNonLastParts() {
        final String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key("k")).uploadId();
        final String small = s3.uploadPart(r -> r.bucket(bucket).key("k").uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(new byte[1024])).eTag();
        final String last = s3.uploadPart(r -> r.bucket(bucket).key("k").uploadId(uploadId).partNumber(2),
                RequestBody.fromBytes(new byte[1024])).eTag();

        assertThatThrownBy(() -> s3.completeMultipartUpload(r -> r.bucket(bucket).key("k").uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(
                        CompletedPart.builder().partNumber(1).eTag(small).build(),
                        CompletedPart.builder().partNumber(2).eTag(last).build()).build())))
                .isInstanceOfSatisfying(S3Exception.class,
                        ex -> assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("EntityTooSmall"));

        // a single small part is fine: the last part has no minimum
        s3.completeMultipartUpload(r -> r.bucket(bucket).key("k").uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(
                        CompletedPart.builder().partNumber(2).eTag(last).build()).build()));
        assertThat(s3.headObject(r -> r.bucket(bucket).key("k")).contentLength()).isEqualTo(1024L);
    }

    // -------------------------------------------------------------------------
    // Authentication errors
    // -------------------------------------------------------------------------

    @Test
    void unknownAccessKeyIsInvalidAccessKeyId() {
        try (S3Client bad = clientWith("AKIANOBODY00000000001", SECRET_KEY)) {
            assertThatThrownBy(() -> bad.listObjectsV2(r -> r.bucket(bucket)))
                    .isInstanceOfSatisfying(S3Exception.class, ex -> {
                        assertThat(ex.statusCode()).isEqualTo(403);
                        assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("InvalidAccessKeyId");
                    });
        }
    }

    @Test
    void wrongSecretIsSignatureDoesNotMatch() {
        try (S3Client bad = clientWith(ACCESS_KEY, "NotTheRightSecretAtAll0000000000000000")) {
            assertThatThrownBy(() -> bad.listObjectsV2(r -> r.bucket(bucket)))
                    .isInstanceOfSatisfying(S3Exception.class, ex -> {
                        assertThat(ex.statusCode()).isEqualTo(403);
                        assertThat(ex.awsErrorDetails().errorCode()).isEqualTo("SignatureDoesNotMatch");
                    });
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void enableVersioning() {
        s3.putBucketVersioning(r -> r.bucket(bucket).versioningConfiguration(
                VersioningConfiguration.builder().status(BucketVersioningStatus.ENABLED).build()));
    }

    private S3Client clientWith(final String accessKey, final String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getPort()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(REGION_NAME))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
