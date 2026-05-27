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
package org.midnightbsd.leonardo.api.controller;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Head;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import org.midnightbsd.leonardo.core.IntegrityChecker;
import org.midnightbsd.leonardo.core.S3Exception;
import org.midnightbsd.leonardo.core.multipart.MultipartService;
import org.midnightbsd.leonardo.core.object.ObjectMetadata;
import org.midnightbsd.leonardo.core.object.ObjectService;
import org.midnightbsd.leonardo.xml.CompleteMultipartUploadRequest;
import org.midnightbsd.leonardo.xml.S3Xml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * S3 object-level endpoints ({@code /<bucket>/<key+>}):
 * <ul>
 *   <li>{@code HEAD} — HeadObject</li>
 *   <li>{@code GET} — GetObject, ListParts ({@code ?uploadId=}), GetObjectAttributes ({@code ?attributes})</li>
 *   <li>{@code PUT} — PutObject, CopyObject ({@code x-amz-copy-source}),
 *       UploadPart ({@code ?partNumber=&uploadId=}),
 *       UploadPartCopy ({@code x-amz-copy-source + ?partNumber=&uploadId=})</li>
 *   <li>{@code POST} — CreateMultipartUpload ({@code ?uploads}),
 *       CompleteMultipartUpload ({@code ?uploadId=})</li>
 *   <li>{@code DELETE} — DeleteObject, AbortMultipartUpload ({@code ?uploadId=})</li>
 * </ul>
 */
@Controller("/{bucket}/{+key}")
public final class ObjectController {

    static final String CALLER_OBJECT_ID_HEADER = "x-leonardo-object-id";

    private static final Logger LOG = LoggerFactory.getLogger(ObjectController.class);
    private static final DateTimeFormatter RFC1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final ObjectService objectService;
    private final MultipartService multipartService;

    public ObjectController(
            final ObjectService objectService,
            final MultipartService multipartService) {
        this.objectService    = objectService;
        this.multipartService = multipartService;
    }

    // -------------------------------------------------------------------------
    // HeadObject
    // -------------------------------------------------------------------------

    @Head
    public HttpResponse<?> headObject(
            @PathVariable final String bucket,
            @PathVariable final String key) {
        try {
            final ObjectMetadata meta = objectService.headObject(bucket, key);
            return objectHeaders(HttpResponse.ok(), meta);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        }
    }

    // -------------------------------------------------------------------------
    // GetObject / ListParts / GetObjectAttributes
    // -------------------------------------------------------------------------

    @Get
    @Produces(MediaType.ALL)
    public HttpResponse<?> getObject(
            @PathVariable final String bucket,
            @PathVariable final String key,
            @QueryValue(value = "uploadId", defaultValue = "") final String uploadId,
            @Header(value = "x-amz-object-attributes", defaultValue = "") final String objectAttributes,
            @QueryValue(value = "part-number-marker", defaultValue = "0") final int partNumberMarker,
            @QueryValue(value = "max-parts", defaultValue = "1000") final int maxParts,
            final HttpRequest<?> request) {

        if (!uploadId.isEmpty()) {
            return handleListParts(bucket, key, uploadId, partNumberMarker, maxParts);
        }
        if (!objectAttributes.isEmpty() || request.getParameters().contains("attributes")) {
            return handleGetObjectAttributes(bucket, key);
        }
        if (request.getParameters().contains("acl")) {
            return handleGetObjectAcl(bucket, key);
        }
        if (request.getParameters().contains("tagging")) {
            return handleGetObjectTagging(bucket, key);
        }
        if (request.getParameters().contains("legal-hold")) {
            return handleGetObjectLegalHold(bucket, key);
        }
        if (request.getParameters().contains("retention")) {
            return handleGetObjectRetention(bucket, key);
        }
        try {
            final ObjectService.GetResult result = objectService.getObject(bucket, key);
            final ObjectMetadata meta = result.meta();
            final MutableHttpResponse<byte[]> response =
                    HttpResponse.ok(result.data());
            objectHeaders(response, meta);
            response.contentType(meta.contentType());
            response.header("Content-Length", String.valueOf(result.data().length));
            return response;
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("GetObject '{}/{}' failed", bucket, key, ex);
            return BucketController.internalError();
        }
    }

    private HttpResponse<String> handleListParts(
            final String bucket,
            final String key,
            final String uploadId,
            final int partNumberMarker,
            final int maxParts) {
        try {
            final var upload = multipartService.getUpload(bucket, uploadId);
            final MultipartService.ListPartsPage page =
                    multipartService.listParts(bucket, key, uploadId, partNumberMarker, maxParts);
            final var partEntries = page.parts().stream()
                    .map(p -> new S3Xml.PartEntry(
                            p.partNumber(), p.lastModified(), p.etag(), p.size()))
                    .toList();
            final String xml = S3Xml.listParts(
                    bucket, key, uploadId,
                    partNumberMarker, maxParts,
                    page.truncated(), page.nextPartNumberMarker(),
                    upload.owner() != null ? upload.owner() : "",
                    "STANDARD", upload.initiated(), partEntries);
            return HttpResponse.ok(xml).contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        }
    }

    private HttpResponse<String> handleGetObjectAttributes(
            final String bucket, final String key) {
        try {
            final ObjectMetadata meta = objectService.headObject(bucket, key);
            final String xml = S3Xml.getObjectAttributesResponse(
                    meta.etag(), meta.size(), meta.storageClass(),
                    meta.partsCount(), meta.checksums());
            return HttpResponse.<String>ok(xml)
                    .contentType(MediaType.APPLICATION_XML)
                    .header("Last-Modified", RFC1123.format(meta.lastModified()));
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        }
    }

    // -------------------------------------------------------------------------
    // PutObject / CopyObject / UploadPart / UploadPartCopy
    // -------------------------------------------------------------------------

    @Put
    @Consumes(MediaType.ALL)
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> putObject(
            @PathVariable final String bucket,
            @PathVariable final String key,
            @QueryValue(value = "partNumber", defaultValue = "0") final int partNumber,
            @QueryValue(value = "uploadId", defaultValue = "") final String uploadId,
            @Header(value = "x-amz-copy-source", defaultValue = "") final String copySource,
            @Header(value = CALLER_OBJECT_ID_HEADER, defaultValue = "") final String callerObjectId,
            @Header(value = "content-type", defaultValue = "") final String contentType,
            @Header(value = "content-md5", defaultValue = "") final String contentMd5,
            @Header(value = "x-amz-acl", defaultValue = "") final String cannedAcl,
            @Header(value = "x-amz-bypass-governance-retention", defaultValue = "false")
                final String bypassGovernanceRetention,
            @Body final byte[] body,
            final HttpRequest<?> request) {

        // Sub-resource dispatches before UploadPart check
        if (request.getParameters().contains("acl")) {
            return handlePutObjectAcl(bucket, key, body, cannedAcl);
        }
        if (request.getParameters().contains("tagging")) {
            return handlePutObjectTagging(bucket, key, body);
        }
        if (request.getParameters().contains("legal-hold")) {
            return handlePutObjectLegalHold(bucket, key, body);
        }
        if (request.getParameters().contains("retention")) {
            return handlePutObjectRetention(bucket, key, body,
                    "true".equalsIgnoreCase(bypassGovernanceRetention));
        }

        // UploadPart or UploadPartCopy
        if (!uploadId.isEmpty() && partNumber > 0) {
            if (!copySource.isEmpty()) {
                return handleUploadPartCopy(bucket, key, uploadId, partNumber, copySource);
            }
            return handleUploadPart(bucket, key, uploadId, partNumber, body);
        }

        // CopyObject
        if (!copySource.isEmpty()) {
            return handleCopyObject(bucket, key, copySource);
        }

        // PutObject
        final byte[] payload = body != null ? body : new byte[0];

        // Verify integrity checksums provided by the client
        try {
            IntegrityChecker.verifyContentMd5(payload, contentMd5.isEmpty() ? null : contentMd5);
            final Map<String, String> requestChecksums = extractChecksums(request);
            IntegrityChecker.verifyAll(payload, requestChecksums);

            final var userMetadata = new HashMap<String, String>();
            request.getHeaders().forEachValue((name, value) -> {
                if (name.toLowerCase().startsWith("x-amz-meta-")) {
                    userMetadata.put(name.toLowerCase(), value);
                }
            });

            final String etag = objectService.putObject(
                    bucket, key,
                    callerObjectId.isEmpty() ? null : callerObjectId,
                    contentType.isEmpty() ? null : contentType,
                    userMetadata.isEmpty() ? null : userMetadata,
                    requestChecksums.isEmpty() ? null : requestChecksums,
                    payload);
            return HttpResponse.<String>ok()
                    .header("ETag", "\"" + etag + "\"");
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("PutObject '{}/{}' failed", bucket, key, ex);
            return BucketController.internalError();
        }
    }

    private HttpResponse<String> handleUploadPart(
            final String bucket,
            final String key,
            final String uploadId,
            final int partNumber,
            final byte[] data) {
        try {
            final byte[] payload = data != null ? data : new byte[0];
            final String etag = multipartService.uploadPart(bucket, key, uploadId, partNumber, payload);
            return HttpResponse.<String>ok()
                    .header("ETag", "\"" + etag + "\"");
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("UploadPart '{}/{}' part {} failed", bucket, key, partNumber, ex);
            return BucketController.internalError();
        }
    }

    private HttpResponse<String> handleUploadPartCopy(
            final String bucket,
            final String key,
            final String uploadId,
            final int partNumber,
            final String rawSource) {
        try {
            String decoded = URLDecoder.decode(rawSource, StandardCharsets.UTF_8);
            if (decoded.startsWith("/")) decoded = decoded.substring(1);
            final int slash = decoded.indexOf('/');
            if (slash < 0) {
                return BucketController.s3Error(
                        S3Xml.error("InvalidArgument",
                                "x-amz-copy-source must be of the form /bucket/key", null), 400);
            }
            final String srcBucket = decoded.substring(0, slash);
            final String srcKey    = decoded.substring(slash + 1);

            final String etag = multipartService.uploadPartCopy(
                    bucket, key, uploadId, partNumber, srcBucket, srcKey, null, null);
            return HttpResponse.ok(
                    S3Xml.copyObjectResult("\"" + etag + "\"", java.time.Instant.now()))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("UploadPartCopy '{}/{}' part {} failed", bucket, key, partNumber, ex);
            return BucketController.internalError();
        }
    }

    private HttpResponse<String> handleCopyObject(
            final String dstBucket, final String dstKey, final String rawSource) {

        String decoded = URLDecoder.decode(rawSource, StandardCharsets.UTF_8);
        if (decoded.startsWith("/")) decoded = decoded.substring(1);
        final int slash = decoded.indexOf('/');
        if (slash < 0) {
            return BucketController.s3Error(
                    S3Xml.error("InvalidArgument",
                            "x-amz-copy-source must be of the form /bucket/key", null), 400);
        }
        final String srcBucket = decoded.substring(0, slash);
        final String srcKey = decoded.substring(slash + 1);

        try {
            final String etag = objectService.copyObject(srcBucket, srcKey, dstBucket, dstKey);
            return HttpResponse.ok(S3Xml.copyObjectResult(
                    "\"" + etag + "\"", java.time.Instant.now()))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("CopyObject '{}/{}'→'{}/{}' failed", srcBucket, srcKey, dstBucket, dstKey, ex);
            return BucketController.internalError();
        }
    }

    // -------------------------------------------------------------------------
    // CreateMultipartUpload / CompleteMultipartUpload
    // -------------------------------------------------------------------------

    @Post
    @Consumes(MediaType.ALL)
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> postObject(
            @PathVariable final String bucket,
            @PathVariable final String key,
            @QueryValue(value = "uploadId", defaultValue = "") final String uploadId,
            @Header(value = "content-type", defaultValue = "") final String contentType,
            @Header(value = CALLER_OBJECT_ID_HEADER, defaultValue = "") final String callerObjectId,
            @Body final byte[] body,
            final HttpRequest<?> request) {

        if (request.getParameters().contains("uploads")) {
            return handleCreateMultipartUpload(bucket, key, contentType, callerObjectId, request);
        }
        if (!uploadId.isEmpty()) {
            return handleCompleteMultipartUpload(bucket, key, uploadId, body);
        }
        if (request.getParameters().contains("restore")) {
            return handleRestoreObject(bucket, key);
        }
        if (request.getParameters().contains("rename")) {
            return handleRenameObject(bucket, key, request);
        }
        return BucketController.s3Error(S3Xml.error("MethodNotAllowed",
                "The specified method is not allowed against this resource.", null), 405);
    }

    private HttpResponse<String> handleCreateMultipartUpload(
            final String bucket,
            final String key,
            final String contentType,
            final String callerObjectId,
            final HttpRequest<?> request) {
        try {
            final var userMetadata = new HashMap<String, String>();
            request.getHeaders().forEachValue((name, value) -> {
                if (name.toLowerCase().startsWith("x-amz-meta-")) {
                    userMetadata.put(name.toLowerCase(), value);
                }
            });
            final String identity = request.getAttribute("s3.identity", String.class)
                    .orElse("anonymous");
            final String uploadId = multipartService.createMultipartUpload(
                    bucket, key, identity,
                    contentType.isEmpty() ? null : contentType,
                    userMetadata.isEmpty() ? null : userMetadata,
                    callerObjectId.isEmpty() ? null : callerObjectId);
            return HttpResponse.ok(S3Xml.initiateMultipartUploadResult(bucket, key, uploadId))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("CreateMultipartUpload '{}/{}' failed", bucket, key, ex);
            return BucketController.internalError();
        }
    }

    private HttpResponse<String> handleCompleteMultipartUpload(
            final String bucket,
            final String key,
            final String uploadId,
            final byte[] body) {
        try {
            final CompleteMultipartUploadRequest req = CompleteMultipartUploadRequest.parse(body);
            final List<MultipartService.PartRef> partRefs = req.parts().stream()
                    .map(p -> new MultipartService.PartRef(p.partNumber(), p.etag()))
                    .toList();
            final MultipartService.CompleteResult result =
                    multipartService.completeMultipartUpload(bucket, key, uploadId, partRefs);
            final String location = "/" + bucket + "/" + key;
            final String xml = S3Xml.completeMultipartUploadResult(
                    location, bucket, key, result.etag());
            return HttpResponse.ok(xml).contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return BucketController.s3Error(
                    S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("CompleteMultipartUpload '{}/{}' uploadId='{}' failed",
                    bucket, key, uploadId, ex);
            return BucketController.internalError();
        }
    }

    // -------------------------------------------------------------------------
    // DeleteObject / AbortMultipartUpload
    // -------------------------------------------------------------------------

    @Delete
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> deleteObject(
            @PathVariable final String bucket,
            @PathVariable final String key,
            @QueryValue(value = "uploadId", defaultValue = "") final String uploadId,
            final HttpRequest<?> request) {
        if (!uploadId.isEmpty()) {
            return handleAbortMultipartUpload(bucket, key, uploadId);
        }
        if (request.getParameters().contains("tagging")) {
            return handleDeleteObjectTagging(bucket, key);
        }
        try {
            objectService.deleteObject(bucket, key);
            return HttpResponse.<String>status(HttpStatus.NO_CONTENT);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("DeleteObject '{}/{}' failed", bucket, key, ex);
            return BucketController.internalError();
        }
    }

    private HttpResponse<String> handleAbortMultipartUpload(
            final String bucket, final String key, final String uploadId) {
        try {
            multipartService.abortMultipartUpload(bucket, key, uploadId);
            return HttpResponse.<String>status(HttpStatus.NO_CONTENT);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("AbortMultipartUpload '{}/{}' uploadId='{}' failed", bucket, key, uploadId, ex);
            return BucketController.internalError();
        }
    }

    // -------------------------------------------------------------------------
    // Phase 4+5 — object configuration GET handlers
    // -------------------------------------------------------------------------

    private HttpResponse<String> handleGetObjectAcl(final String bucket, final String key) {
        try {
            final ObjectMetadata meta = objectService.headObject(bucket, key);
            final String canned = meta.aclCanned() != null ? meta.aclCanned() : "private";
            return HttpResponse.ok(S3Xml.getObjectAcl(null, canned, null))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        }
    }

    private HttpResponse<String> handleGetObjectTagging(final String bucket, final String key) {
        try {
            final ObjectMetadata meta = objectService.headObject(bucket, key);
            return HttpResponse.ok(S3Xml.getObjectTagging(meta.tags()))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        }
    }

    private HttpResponse<String> handleGetObjectLegalHold(final String bucket, final String key) {
        try {
            final ObjectMetadata meta = objectService.headObject(bucket, key);
            return HttpResponse.ok(S3Xml.getObjectLegalHold(meta.legalHold()))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        }
    }

    private HttpResponse<String> handleGetObjectRetention(final String bucket, final String key) {
        try {
            final ObjectMetadata meta = objectService.headObject(bucket, key);
            if (meta.retention() == null) {
                return BucketController.s3Error(
                        S3Xml.error("NoSuchObjectLockConfiguration",
                                "The specified object does not have a ObjectLock configuration.", null),
                        404);
            }
            return HttpResponse.ok(S3Xml.getObjectRetention(
                    meta.retention().mode(), meta.retention().retainUntilDate()))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 4+5 — object configuration PUT handlers
    // -------------------------------------------------------------------------

    private HttpResponse<String> handlePutObjectAcl(
            final String bucket, final String key,
            final byte[] body, final String cannedAcl) {
        try {
            final String acl = ObjectConfigParser.parseObjectAcl(body, cannedAcl);
            objectService.updateObject(bucket, key, meta -> meta.withAclCanned(acl));
            return HttpResponse.<String>ok();
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return BucketController.s3Error(
                    S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutObjectAcl '{}/{}' failed", bucket, key, ex);
            return BucketController.internalError();
        }
    }

    private HttpResponse<String> handlePutObjectTagging(
            final String bucket, final String key, final byte[] body) {
        try {
            final Map<String, String> tags = ObjectConfigParser.parseObjectTagging(body);
            objectService.updateObject(bucket, key,
                    meta -> meta.withTags(tags.isEmpty() ? null : tags));
            return HttpResponse.<String>ok();
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return BucketController.s3Error(
                    S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutObjectTagging '{}/{}' failed", bucket, key, ex);
            return BucketController.internalError();
        }
    }

    private HttpResponse<String> handlePutObjectLegalHold(
            final String bucket, final String key, final byte[] body) {
        try {
            final boolean hold = ObjectConfigParser.parseObjectLegalHold(body);
            objectService.updateObject(bucket, key, meta -> meta.withLegalHold(hold));
            return HttpResponse.<String>ok();
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return BucketController.s3Error(
                    S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutObjectLegalHold '{}/{}' failed", bucket, key, ex);
            return BucketController.internalError();
        }
    }

    private HttpResponse<String> handlePutObjectRetention(
            final String bucket, final String key,
            final byte[] body, final boolean bypassGovernance) {
        try {
            final ObjectMetadata.RetentionConfig retention =
                    ObjectConfigParser.parseObjectRetention(body);
            objectService.updateObject(bucket, key, meta -> {
                if (!bypassGovernance
                        && meta.retention() != null
                        && "COMPLIANCE".equals(meta.retention().mode())) {
                    throw new S3Exception("AccessDenied",
                            "Access Denied: COMPLIANCE retention cannot be shortened.", 403);
                }
                return meta.withRetention(retention);
            });
            return HttpResponse.<String>ok();
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return BucketController.s3Error(
                    S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutObjectRetention '{}/{}' failed", bucket, key, ex);
            return BucketController.internalError();
        }
    }

    // -------------------------------------------------------------------------
    // Phase 4+5 — object tagging DELETE
    // -------------------------------------------------------------------------

    private HttpResponse<String> handleDeleteObjectTagging(final String bucket, final String key) {
        try {
            objectService.updateObject(bucket, key, meta -> meta.withTags(null));
            return HttpResponse.<String>status(HttpStatus.NO_CONTENT);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("DeleteObjectTagging '{}/{}' failed", bucket, key, ex);
            return BucketController.internalError();
        }
    }

    // -------------------------------------------------------------------------
    // Phase 5 — RestoreObject + RenameObject
    // -------------------------------------------------------------------------

    private HttpResponse<String> handleRestoreObject(final String bucket, final String key) {
        try {
            objectService.headObject(bucket, key); // 404 if not found
            // Leonardo uses local storage only; all objects are immediately accessible.
            return HttpResponse.<String>status(HttpStatus.OK);
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        }
    }

    private HttpResponse<String> handleRenameObject(
            final String bucket, final String key, final HttpRequest<?> request) {
        final String srcKey = request.getHeaders()
                .get("x-amz-rename-source-key");
        if (srcKey == null || srcKey.isEmpty()) {
            return BucketController.s3Error(S3Xml.error("InvalidArgument",
                    "x-amz-rename-source-key header is required for rename.", null), 400);
        }
        try {
            objectService.renameObject(bucket, srcKey, key);
            return HttpResponse.<String>ok();
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("RenameObject '{}/{}' → '{}' failed", bucket, srcKey, key, ex);
            return BucketController.internalError();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static <T> MutableHttpResponse<T> objectHeaders(
            final MutableHttpResponse<T> response, final ObjectMetadata meta) {
        response.header("ETag", meta.etag());
        response.header("Last-Modified", RFC1123.format(meta.lastModified()));
        if (meta.size() >= 0) {
            response.header("Content-Length", String.valueOf(meta.size()));
        }
        return response;
    }

    /** Extracts {@code x-amz-checksum-*} headers into a Map (algorithm → Base64 value). */
    private static Map<String, String> extractChecksums(final HttpRequest<?> request) {
        final var result = new HashMap<String, String>();
        request.getHeaders().forEachValue((name, value) -> {
            final String lower = name.toLowerCase();
            if (lower.startsWith("x-amz-checksum-")) {
                final String alg = lower.substring("x-amz-checksum-".length());
                result.put(alg, value);
            }
        });
        return result;
    }
}
