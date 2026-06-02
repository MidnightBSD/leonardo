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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conformance tests for multipart upload: CreateMultipartUpload, UploadPart,
 * UploadPartCopy, CompleteMultipartUpload, AbortMultipartUpload,
 * ListMultipartUploads, and ListParts.
 *
 * <p>Part sizes use 110 KiB to stay above the conformance-env minimum of 100 KiB.
 */
final class MultipartConformanceTest extends ConformanceBase {

    /** 110 KiB — just above the 100 KiB min part size configured for the test env. */
    private static final int PART_SIZE = 110 * 1024;
    private static final String KEY = "multipart-object";

    // -------------------------------------------------------------------------
    // Abort
    // -------------------------------------------------------------------------

    @Test
    void abortMultipartUploadLeavesNoObject() {
        final CreateMultipartUploadResponse create =
                s3.createMultipartUpload(r -> r.bucket(bucket).key(KEY));
        final String uploadId = create.uploadId();
        assertThat(uploadId).isNotBlank();

        s3.uploadPart(r -> r.bucket(bucket).key(KEY).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(makeBytes(PART_SIZE, (byte) 0xAB)));

        final AbortMultipartUploadResponse abort =
                s3.abortMultipartUpload(r -> r.bucket(bucket).key(KEY).uploadId(uploadId));
        assertThat(abort.sdkHttpResponse().statusCode()).isEqualTo(204);

        assertThatThrownBy(() -> s3.headObject(r -> r.bucket(bucket).key(KEY)))
                .isInstanceOf(NoSuchKeyException.class);
    }

    // -------------------------------------------------------------------------
    // Complete (single part)
    // -------------------------------------------------------------------------

    @Test
    void completeMultipartUploadWithSinglePart() {
        final byte[] data = makeBytes(PART_SIZE, (byte) 0x42);

        final CreateMultipartUploadResponse create =
                s3.createMultipartUpload(r -> r.bucket(bucket).key(KEY).contentType("application/octet-stream"));
        final String uploadId = create.uploadId();

        final UploadPartResponse part1 = s3.uploadPart(
                r -> r.bucket(bucket).key(KEY).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(data));
        final String etag1 = part1.eTag();
        assertThat(etag1).isNotBlank();

        final CompleteMultipartUploadResponse done = s3.completeMultipartUpload(r -> r
                .bucket(bucket).key(KEY).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(CompletedPart.builder().partNumber(1).eTag(etag1).build())
                        .build()));
        assertThat(done.sdkHttpResponse().statusCode()).isEqualTo(200);
        assertThat(done.eTag()).isNotBlank();

        // Object should now be accessible
        final byte[] got = s3.getObjectAsBytes(r -> r.bucket(bucket).key(KEY)).asByteArray();
        assertThat(got).isEqualTo(data);
    }

    // -------------------------------------------------------------------------
    // Complete (multiple parts)
    // -------------------------------------------------------------------------

    @Test
    void completeMultipartUploadWithThreeParts() {
        final byte[] p1 = makeBytes(PART_SIZE, (byte) 0x11);
        final byte[] p2 = makeBytes(PART_SIZE, (byte) 0x22);
        final byte[] p3 = makeBytes(PART_SIZE, (byte) 0x33);

        final CreateMultipartUploadResponse create =
                s3.createMultipartUpload(r -> r.bucket(bucket).key(KEY));
        final String uploadId = create.uploadId();

        final String e1 = s3.uploadPart(
                r -> r.bucket(bucket).key(KEY).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(p1)).eTag();
        final String e2 = s3.uploadPart(
                r -> r.bucket(bucket).key(KEY).uploadId(uploadId).partNumber(2),
                RequestBody.fromBytes(p2)).eTag();
        final String e3 = s3.uploadPart(
                r -> r.bucket(bucket).key(KEY).uploadId(uploadId).partNumber(3),
                RequestBody.fromBytes(p3)).eTag();

        s3.completeMultipartUpload(r -> r
                .bucket(bucket).key(KEY).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(
                        CompletedPart.builder().partNumber(1).eTag(e1).build(),
                        CompletedPart.builder().partNumber(2).eTag(e2).build(),
                        CompletedPart.builder().partNumber(3).eTag(e3).build()
                ).build()));

        final byte[] expected = concat(p1, p2, p3);
        final byte[] got = s3.getObjectAsBytes(r -> r.bucket(bucket).key(KEY)).asByteArray();
        assertThat(got).isEqualTo(expected);
    }

    // -------------------------------------------------------------------------
    // ListMultipartUploads
    // -------------------------------------------------------------------------

    @Test
    void listMultipartUploadsShowsOngoingUploads() {
        final String upload1 = s3.createMultipartUpload(r -> r.bucket(bucket).key("obj-1"))
                .uploadId();
        final String upload2 = s3.createMultipartUpload(r -> r.bucket(bucket).key("obj-2"))
                .uploadId();

        final ListMultipartUploadsResponse list =
                s3.listMultipartUploads(r -> r.bucket(bucket));
        final List<String> uploadIds = list.uploads().stream()
                .map(MultipartUpload::uploadId)
                .toList();

        assertThat(uploadIds).contains(upload1, upload2);

        // clean up
        s3.abortMultipartUpload(r -> r.bucket(bucket).key("obj-1").uploadId(upload1));
        s3.abortMultipartUpload(r -> r.bucket(bucket).key("obj-2").uploadId(upload2));
    }

    // -------------------------------------------------------------------------
    // ListParts
    // -------------------------------------------------------------------------

    @Test
    void listPartsShowsUploadedParts() {
        final CreateMultipartUploadResponse create =
                s3.createMultipartUpload(r -> r.bucket(bucket).key(KEY));
        final String uploadId = create.uploadId();

        final String e1 = s3.uploadPart(
                r -> r.bucket(bucket).key(KEY).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(makeBytes(PART_SIZE, (byte) 0xAA))).eTag();
        final String e2 = s3.uploadPart(
                r -> r.bucket(bucket).key(KEY).uploadId(uploadId).partNumber(2),
                RequestBody.fromBytes(makeBytes(PART_SIZE, (byte) 0xBB))).eTag();

        final ListPartsResponse parts = s3.listParts(r ->
                r.bucket(bucket).key(KEY).uploadId(uploadId));

        assertThat(parts.parts()).hasSize(2);
        assertThat(parts.parts().get(0).partNumber()).isEqualTo(1);
        assertThat(parts.parts().get(0).eTag()).isEqualTo(e1);
        assertThat(parts.parts().get(1).partNumber()).isEqualTo(2);
        assertThat(parts.parts().get(1).eTag()).isEqualTo(e2);

        s3.abortMultipartUpload(r -> r.bucket(bucket).key(KEY).uploadId(uploadId));
    }

    // -------------------------------------------------------------------------
    // UploadPartCopy
    // -------------------------------------------------------------------------

    @Test
    void uploadPartCopyCopiesFromExistingObject() {
        // Put the source object
        final byte[] srcData = makeBytes(PART_SIZE, (byte) 0x55);
        s3.putObject(r -> r.bucket(bucket).key("source.dat"), RequestBody.fromBytes(srcData));

        final CreateMultipartUploadResponse create =
                s3.createMultipartUpload(r -> r.bucket(bucket).key(KEY));
        final String uploadId = create.uploadId();

        final String etag = s3.uploadPartCopy(r -> r
                .sourceBucket(bucket).sourceKey("source.dat")
                .destinationBucket(bucket).destinationKey(KEY)
                .uploadId(uploadId).partNumber(1))
                .copyPartResult().eTag();
        assertThat(etag).isNotBlank();

        s3.completeMultipartUpload(r -> r
                .bucket(bucket).key(KEY).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(CompletedPart.builder().partNumber(1).eTag(etag).build())
                        .build()));

        final byte[] got = s3.getObjectAsBytes(r -> r.bucket(bucket).key(KEY)).asByteArray();
        assertThat(got).isEqualTo(srcData);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static byte[] makeBytes(final int size, final byte fill) {
        final byte[] b = new byte[size];
        Arrays.fill(b, fill);
        return b;
    }

    private static byte[] concat(final byte[]... parts) {
        int total = 0;
        for (final byte[] p : parts) {
            total += p.length;
        }
        final byte[] out = new byte[total];
        int pos = 0;
        for (final byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
