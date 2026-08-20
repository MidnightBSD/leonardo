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
import java.io.InputStream;
import java.util.Optional;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
    private static final int MAX_CONTROL_BODY_BYTES = 10 * 1024 * 1024;

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
            @PathVariable final String key,
            @QueryValue(value = "versionId", defaultValue = "") final String versionId,
            @Header(value = "if-none-match", defaultValue = "") final String ifNoneMatch,
            @Header(value = "if-match", defaultValue = "") final String ifMatch,
            @Header(value = "if-modified-since", defaultValue = "") final String ifModifiedSince,
            @Header(value = "if-unmodified-since", defaultValue = "") final String ifUnmodifiedSince) {
        try {
            final ObjectMetadata meta = versionId.isEmpty()
                    ? objectService.headObject(bucket, key)
                    : objectService.headObjectVersion(bucket, key, versionId);
            final HttpResponse<?> conditional = checkConditionals(
                    meta, ifMatch, ifNoneMatch, ifModifiedSince, ifUnmodifiedSince);
            if (conditional != null) return conditional;
            final MutableHttpResponse<?> resp = objectHeaders(HttpResponse.ok(), meta);
            resp.header("Accept-Ranges", "bytes");
            if (meta.versionId() != null) {
                resp.header("x-amz-version-id", meta.versionId());
            }
            return resp;
        } catch (final S3Exception ex) {
            // HEAD responses must have an empty body per HTTP spec
            return HttpResponse.status(HttpStatus.valueOf(ex.getHttpStatus()));
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
            @QueryValue(value = "versionId", defaultValue = "") final String versionId,
            @Header(value = "x-amz-object-attributes", defaultValue = "") final String objectAttributes,
            @QueryValue(value = "part-number-marker", defaultValue = "0") final int partNumberMarker,
            @QueryValue(value = "max-parts", defaultValue = "1000") final int maxParts,
            @Header(value = "range", defaultValue = "") final String rangeHeader,
            @Header(value = "if-none-match", defaultValue = "") final String ifNoneMatch,
            @Header(value = "if-match", defaultValue = "") final String ifMatch,
            @Header(value = "if-modified-since", defaultValue = "") final String ifModifiedSince,
            @Header(value = "if-unmodified-since", defaultValue = "") final String ifUnmodifiedSince,
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
        if (request.getParameters().contains("torrent")) {
            return BucketController.s3Error(S3Xml.error("NotImplemented",
                    "GetObjectTorrent is not supported by this server.", null), 501);
        }
        try {
            final ObjectService.GetStreamResult result = versionId.isEmpty()
                    ? objectService.openObject(bucket, key)
                    : objectService.openObjectVersion(bucket, key, versionId);
            final ObjectMetadata meta = result.meta();

            // Conditional request checks (RFC 7232 precedence order)
            final HttpResponse<?> conditional = checkConditionals(
                    meta, ifMatch, ifNoneMatch, ifModifiedSince, ifUnmodifiedSince);
            if (conditional != null) {
                result.data().close();
                return conditional;
            }

            final MutableHttpResponse<InputStream> response;

            // Range request support
            if (!rangeHeader.isEmpty()) {
                final long[] range = parseRange(rangeHeader, meta.size());
                if (range != null) {
                    final long start = range[0];
                    final long end   = range[1];
                    result.data().skipNBytes(start);
                    final InputStream slice = new LimitedInputStream(result.data(), end - start + 1);
                    response = HttpResponse.status(HttpStatus.PARTIAL_CONTENT, "Partial Content")
                            .body(slice);
                    objectHeaders(response, meta);
                    response.contentType(meta.contentType());
                    response.header("Content-Range",
                            "bytes " + start + "-" + end + "/" + meta.size());
                    response.header("Content-Length", String.valueOf(end - start + 1));
                    response.header("Accept-Ranges", "bytes");
                    return response;
                }
                // Invalid range → 416
                return HttpResponse.<String>status(HttpStatus.valueOf(416))
                        .header("Content-Range", "bytes */" + meta.size());
            }

            response = HttpResponse.ok(result.data());
            objectHeaders(response, meta);
            response.contentType(meta.contentType());
            response.header("Content-Length", String.valueOf(meta.size()));
            response.header("Accept-Ranges", "bytes");
            if (meta.versionId() != null) {
                response.header("x-amz-version-id", meta.versionId());
            }
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
            @Header(value = "content-encoding", defaultValue = "") final String contentEncoding,
            @Header(value = "x-amz-content-sha256", defaultValue = "") final String payloadHash,
            @Body final InputStream body,
            final HttpRequest<?> request) {
        // Sub-resource dispatches before UploadPart check
        if (request.getParameters().contains("acl")) {
            return handlePutObjectAcl(bucket, key, readControlBody(body), cannedAcl);
        }
        if (request.getParameters().contains("tagging")) {
            return handlePutObjectTagging(bucket, key, readControlBody(body));
        }
        if (request.getParameters().contains("legal-hold")) {
            return handlePutObjectLegalHold(bucket, key, readControlBody(body));
        }
        if (request.getParameters().contains("retention")) {
            return handlePutObjectRetention(bucket, key, readControlBody(body),
                    "true".equalsIgnoreCase(bypassGovernanceRetention));
        }

        // UploadPart or UploadPartCopy
        if (!uploadId.isEmpty() && partNumber > 0) {
            if (!copySource.isEmpty()) {
                return handleUploadPartCopy(bucket, key, uploadId, partNumber, copySource);
            }
            return handleUploadPart(bucket, key, uploadId, partNumber, body,
                    contentEncoding, payloadHash,
                    request.getAttribute("s3.sigv4Streaming", org.midnightbsd.leonardo.auth.SigV4StreamingContext.class)
                            .orElse(null), request.getHeaders().get("x-amz-trailer"));
        }

        // CopyObject
        if (!copySource.isEmpty()) {
            return handleCopyObject(bucket, key, copySource);
        }

        // PutObject — decode aws-chunked framing if present (AWS CLI v2 default)
        final Map<String, String> requestChecksums = extractChecksums(request);
        final org.midnightbsd.leonardo.auth.SigV4StreamingContext streamingContext = request
                .getAttribute("s3.sigv4Streaming", org.midnightbsd.leonardo.auth.SigV4StreamingContext.class)
                .orElse(null);
        if (payloadHash.startsWith("STREAMING-AWS4-HMAC-SHA256-PAYLOAD") && streamingContext == null) {
            return BucketController.s3Error(S3Xml.error("SignatureDoesNotMatch",
                    "The streaming payload has no verified SigV4 signing context.", null), 403);
        }
        final InputStream payload;
        try {
            payload = AwsChunkedDecoder.isChunked(
                    contentEncoding.isEmpty() ? null : contentEncoding,
                    payloadHash.isEmpty() ? null : payloadHash)
                    ? AwsChunkedDecoder.decodeStream(body,
                            streamingContext,
                            request.getHeaders().get("x-amz-trailer"), requestChecksums)
                    : body;
        } catch (final IllegalArgumentException ex) {
            return BucketController.s3Error(S3Xml.error("InvalidRequest", ex.getMessage(), null), 400);
        }

        // Verify integrity checksums provided by the client
        try {
            final var userMetadata = new HashMap<String, String>();
            request.getHeaders().forEachValue((name, value) -> {
                if (name.toLowerCase().startsWith("x-amz-meta-")) {
                    userMetadata.put(name.toLowerCase(), value);
                }
            });
            final String storedContentEncoding = storageContentEncoding(contentEncoding);
            if (storedContentEncoding != null) {
                // aws-chunked is transport framing, not object metadata. Other
                // encodings (for example gzip) describe the stored object and
                // must be returned by GET and HEAD just as S3 does.
                userMetadata.put("Content-Encoding", storedContentEncoding);
            }

            final ObjectService.PutResult result = objectService.putObject(
                    bucket, key,
                    callerObjectId.isEmpty() ? null : callerObjectId,
                    contentType.isEmpty() ? null : contentType,
                    userMetadata.isEmpty() ? null : userMetadata,
                    requestChecksums.isEmpty() ? null : requestChecksums,
                    contentMd5.isEmpty() ? null : contentMd5, payload);
            final MutableHttpResponse<String> putResp = HttpResponse.<String>ok()
                    .header("ETag", "\"" + result.etag() + "\"");
            if (result.versionId() != null) {
                putResp.header("x-amz-version-id", result.versionId());
            }
            return putResp;
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
            final InputStream data,
            final String contentEncoding,
            final String payloadHash,
            final org.midnightbsd.leonardo.auth.SigV4StreamingContext streamingContext,
            final String trailerHeaderNames) {
        try {
            if (payloadHash.startsWith("STREAMING-AWS4-HMAC-SHA256-PAYLOAD") && streamingContext == null) {
                return BucketController.s3Error(S3Xml.error("SignatureDoesNotMatch",
                        "The streaming payload has no verified SigV4 signing context.", null), 403);
            }
            final InputStream payload = AwsChunkedDecoder.isChunked(
                    contentEncoding.isEmpty() ? null : contentEncoding,
                    payloadHash.isEmpty() ? null : payloadHash)
                    ? AwsChunkedDecoder.decodeStream(data,
                            streamingContext, trailerHeaderNames, new HashMap<>()) : data;
            final String etag = multipartService.uploadPart(bucket, key, uploadId, partNumber, payload);
            return HttpResponse.<String>ok()
                    .header("ETag", "\"" + etag + "\"");
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return BucketController.s3Error(
                    S3Xml.error("InvalidArgument", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("UploadPart '{}/{}' part {} failed", bucket, key, partNumber, ex);
            return BucketController.internalError();
        }
    }

    private static byte[] readControlBody(final InputStream body) {
        try {
            final byte[] bytes = body.readNBytes(MAX_CONTROL_BODY_BYTES + 1);
            if (bytes.length > MAX_CONTROL_BODY_BYTES) {
                throw new IllegalArgumentException("Control request body exceeds "
                        + MAX_CONTROL_BODY_BYTES + " bytes");
            }
            return bytes;
        } catch (final IOException ex) {
            throw new IllegalArgumentException("Unable to read request body", ex);
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
            @Body final Optional<byte[]> body,
            final HttpRequest<?> request) {

        final byte[] xmlBody = body.orElse(null);
        if (request.getParameters().contains("uploads")) {
            return handleCreateMultipartUpload(bucket, key, contentType, callerObjectId, request);
        }
        if (!uploadId.isEmpty()) {
            return handleCompleteMultipartUpload(bucket, key, uploadId, xmlBody);
        }
        if (request.getParameters().contains("restore")) {
            return handleRestoreObject(bucket, key);
        }
        if (request.getParameters().contains("rename")) {
            return handleRenameObject(bucket, key, request);
        }
        if (request.getParameters().contains("select")) {
            return BucketController.s3Error(S3Xml.error("NotImplemented",
                    "SelectObjectContent is not supported by this server.", null), 501);
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
            @QueryValue(value = "versionId", defaultValue = "") final String versionId,
            @Header(value = "x-amz-bypass-governance-retention", defaultValue = "false")
                final String bypassGovernanceHeader,
            final HttpRequest<?> request) {
        if (!uploadId.isEmpty()) {
            return handleAbortMultipartUpload(bucket, key, uploadId);
        }
        if (request.getParameters().contains("tagging")) {
            return handleDeleteObjectTagging(bucket, key);
        }
        final boolean bypass = "true".equalsIgnoreCase(bypassGovernanceHeader);
        try {
            if (!versionId.isEmpty()) {
                objectService.deleteObjectVersion(bucket, key, versionId, bypass);
                return HttpResponse.<String>status(HttpStatus.NO_CONTENT);
            }
            final ObjectService.DeleteObjectResult result =
                    objectService.deleteObject(bucket, key, bypass);
            final MutableHttpResponse<String> resp =
                    HttpResponse.<String>status(HttpStatus.NO_CONTENT);
            if (result.isDeleteMarker()) {
                resp.header("x-amz-delete-marker", "true");
                resp.header("x-amz-version-id", result.deleteMarkerVersionId());
            }
            return resp;
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

    /**
     * Implements RFC 7232 conditional request semantics (If-Match / If-None-Match /
     * If-Modified-Since / If-Unmodified-Since). Returns a short-circuit response
     * (304 or 412) or {@code null} if the request should proceed normally.
     */
    static HttpResponse<?> checkConditionals(
            final ObjectMetadata meta,
            final String ifMatch,
            final String ifNoneMatch,
            final String ifModifiedSince,
            final String ifUnmodifiedSince) {

        final String etag = meta.etag();
        final Instant lastMod = meta.lastModified();

        // If-Match: fail with 412 if ETag doesn't match
        if (!ifMatch.isEmpty()) {
            if (!etagMatches(etag, ifMatch)) {
                return HttpResponse.status(HttpStatus.PRECONDITION_FAILED);
            }
        }

        // S3 gives a successful If-Match precedence over a failing
        // If-Unmodified-Since condition (unlike applying both RFC tests blindly).
        if (ifMatch.isEmpty() && !ifUnmodifiedSince.isEmpty()) {
            final Instant since = parseHttpDate(ifUnmodifiedSince);
            if (since != null && lastMod != null && lastMod.isAfter(since)) {
                return HttpResponse.status(HttpStatus.PRECONDITION_FAILED);
            }
        }

        // If-None-Match: return 304 if ETag matches (cache hit)
        if (!ifNoneMatch.isEmpty()) {
            if (etagMatches(etag, ifNoneMatch)) {
                return HttpResponse.notModified();
            }
        }

        // If-Modified-Since: return 304 if not modified since date
        if (!ifModifiedSince.isEmpty()) {
            final Instant since = parseHttpDate(ifModifiedSince);
            if (since != null && lastMod != null && !lastMod.isAfter(since)) {
                return HttpResponse.notModified();
            }
        }

        return null;
    }

    /** Returns true if {@code responseEtag} equals any of the ETags in the header value. */
    private static boolean etagMatches(final String responseEtag, final String headerValue) {
        if ("*".equals(headerValue.trim())) return responseEtag != null;
        if (responseEtag == null) return false;
        final String normResponse = unquote(responseEtag);
        for (final String candidate : headerValue.split(",")) {
            if (normResponse.equalsIgnoreCase(unquote(candidate))) return true;
        }
        return false;
    }

    private static String unquote(final String s) {
        if (s == null) return "";
        final String trimmed = s.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static Instant parseHttpDate(final String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Instant.from(RFC1123.parse(value));
        } catch (final DateTimeParseException ex) {
            return null;
        }
    }

    /** Limits a response stream to the requested inclusive byte range. */
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private long remaining;

        private LimitedInputStream(final InputStream delegate, final long remaining) {
            super(delegate);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) return -1;
            final int value = in.read();
            if (value != -1) remaining--;
            return value;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            if (remaining == 0) return -1;
            final int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
    }

    /**
     * Parses a {@code Range: bytes=start-end} header value.
     *
     * @param rangeHeader the raw Range header value
     * @param totalLength total number of bytes in the object
     * @return {@code [start, end]} (inclusive, zero-based), or {@code null} if the range
     *         is not satisfiable or the syntax is unrecognised
     */
    static long[] parseRange(final String rangeHeader, final long totalLength) {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=") || totalLength <= 0) return null;
        final String spec = rangeHeader.substring("bytes=".length()).trim();
        final int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            final String startStr = spec.substring(0, dash).trim();
            final String endStr   = spec.substring(dash + 1).trim();
            long start, end;
            if (startStr.isEmpty()) {
                // Suffix range: bytes=-N (last N bytes)
                final long suffix = Long.parseLong(endStr);
                start = Math.max(0, totalLength - suffix);
                end   = totalLength - 1;
            } else {
                start = Long.parseLong(startStr);
                end   = endStr.isEmpty() ? totalLength - 1 : Long.parseLong(endStr);
            }
            if (start < 0 || start >= totalLength || end < start) return null;
            end = Math.min(end, totalLength - 1);
            return new long[]{start, end};
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    private static <T> MutableHttpResponse<T> objectHeaders(
            final MutableHttpResponse<T> response, final ObjectMetadata meta) {
        response.header("ETag", meta.etag());
        response.header("Last-Modified", RFC1123.format(meta.lastModified()));
        if (meta.size() >= 0) {
            response.header("Content-Length", String.valueOf(meta.size()));
        }
        if (meta.userMetadata() != null) {
            meta.userMetadata().forEach(response::header);
        }
        return response;
    }

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

    /** Removes the transport-only aws-chunked token while retaining object encodings. */
    static String storageContentEncoding(final String contentEncoding) {
        if (contentEncoding == null || contentEncoding.isBlank()) return null;
        final String result = java.util.Arrays.stream(contentEncoding.split(","))
                .map(String::trim)
                .filter(value -> !value.equalsIgnoreCase("aws-chunked"))
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.joining(", "));
        return result.isEmpty() ? null : result;
    }
}
