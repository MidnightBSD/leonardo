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
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Head;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import org.midnightbsd.leonardo.core.S3Exception;
import org.midnightbsd.leonardo.core.bucket.BucketService;
import org.midnightbsd.leonardo.core.multipart.MultipartService;
import org.midnightbsd.leonardo.core.object.ObjectMetadataStore;
import org.midnightbsd.leonardo.core.object.ObjectService;
import org.midnightbsd.leonardo.xml.DeleteObjectsRequest;
import org.midnightbsd.leonardo.xml.S3Xml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * S3 bucket-level endpoints:
 * <ul>
 *   <li>{@code HEAD /<bucket>} — HeadBucket</li>
 *   <li>{@code PUT /<bucket>} — CreateBucket</li>
 *   <li>{@code DELETE /<bucket>} — DeleteBucket</li>
 *   <li>{@code GET /<bucket>} — ListObjectsV2 / ListObjects / GetBucketLocation</li>
 *   <li>{@code POST /<bucket>?delete} — DeleteObjects</li>
 * </ul>
 */
@Controller("/{bucket}")
public final class BucketController {

    private static final Logger LOG = LoggerFactory.getLogger(BucketController.class);

    private final BucketService bucketService;
    private final ObjectService objectService;
    private final MultipartService multipartService;

    public BucketController(
            final BucketService bucketService,
            final ObjectService objectService,
            final MultipartService multipartService) {
        this.bucketService    = bucketService;
        this.objectService    = objectService;
        this.multipartService = multipartService;
    }

    // -------------------------------------------------------------------------
    // HeadBucket
    // -------------------------------------------------------------------------

    @Head
    public HttpResponse<?> headBucket(@PathVariable final String bucket) {
        try {
            bucketService.headBucket(bucket);
            return HttpResponse.ok();
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    // -------------------------------------------------------------------------
    // CreateBucket
    // -------------------------------------------------------------------------

    @Put
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> createBucket(
            @PathVariable final String bucket,
            @Body final byte[] body,
            final HttpRequest<?> request) {

        final String identity = request.getAttribute("s3.identity", String.class)
                .orElse("anonymous");
        final String locationConstraint = parseLocationConstraint(body);

        try {
            bucketService.createBucket(bucket, identity, locationConstraint);
            return HttpResponse.<String>ok()
                    .header("Location", "/" + bucket);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("CreateBucket '{}' failed", bucket, ex);
            return internalError();
        }
    }

    // -------------------------------------------------------------------------
    // DeleteBucket
    // -------------------------------------------------------------------------

    @Delete
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> deleteBucket(@PathVariable final String bucket) {
        try {
            bucketService.deleteBucket(bucket);
            return HttpResponse.<String>status(HttpStatus.NO_CONTENT);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("DeleteBucket '{}' failed", bucket, ex);
            return internalError();
        }
    }

    // -------------------------------------------------------------------------
    // GET /<bucket> — ListObjectsV2 / ListObjects / GetBucketLocation
    // -------------------------------------------------------------------------

    @Get
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> getBucket(
            @PathVariable final String bucket,
            @QueryValue(value = "location", defaultValue = "") final String location,
            @QueryValue(value = "list-type", defaultValue = "") final String listType,
            @QueryValue(value = "prefix", defaultValue = "") final String prefix,
            @QueryValue(value = "delimiter", defaultValue = "") final String delimiter,
            @QueryValue(value = "max-keys", defaultValue = "1000") final int maxKeys,
            @QueryValue(value = "continuation-token", defaultValue = "") final String continuationToken,
            @QueryValue(value = "start-after", defaultValue = "") final String startAfter,
            @QueryValue(value = "marker", defaultValue = "") final String marker,
            @QueryValue(value = "key-marker", defaultValue = "") final String keyMarker,
            @QueryValue(value = "upload-id-marker", defaultValue = "") final String uploadIdMarker,
            @QueryValue(value = "max-uploads", defaultValue = "1000") final int maxUploads,
            final HttpRequest<?> request) {

        // GetBucketLocation
        if (request.getParameters().contains("location")) {
            return getBucketLocation(bucket);
        }

        // ListMultipartUploads
        if (request.getParameters().contains("uploads")) {
            return listMultipartUploads(bucket, prefix, delimiter,
                    keyMarker, uploadIdMarker, maxUploads);
        }

        // ListObjectsV2 (list-type=2) or ListObjects v1
        final boolean isV2 = "2".equals(listType);
        try {
            bucketService.headBucket(bucket); // 404 if bucket missing

            final String startAfterKey;
            if (isV2) {
                // continuationToken takes precedence over start-after
                if (!continuationToken.isEmpty()) {
                    startAfterKey = S3Xml.decodeContinuationToken(continuationToken);
                } else {
                    startAfterKey = startAfter.isEmpty() ? null : startAfter;
                }
            } else {
                startAfterKey = marker.isEmpty() ? null : marker;
            }

            final ObjectMetadataStore.ListPage page = objectService.listObjects(
                    bucket,
                    prefix.isEmpty() ? null : prefix,
                    delimiter.isEmpty() ? null : delimiter,
                    maxKeys,
                    startAfterKey);

            final List<S3Xml.ObjectEntry> entries = page.objects().stream()
                    .map(m -> new S3Xml.ObjectEntry(
                            m.key(), m.lastModified(), m.etag(), m.size(), m.storageClass()))
                    .toList();
            final List<S3Xml.CommonPrefixEntry> cpEntries = page.commonPrefixes().stream()
                    .map(S3Xml.CommonPrefixEntry::new)
                    .toList();

            final String xml;
            if (isV2) {
                final String nextToken = page.truncated() && page.nextKey() != null
                        ? S3Xml.encodeContinuationToken(page.nextKey()) : null;
                xml = S3Xml.listObjectsV2(
                        bucket, prefix, delimiter, maxKeys,
                        page.truncated(), continuationToken.isEmpty() ? null : continuationToken,
                        nextToken, entries.size(), entries, cpEntries);
            } else {
                xml = S3Xml.listObjects(
                        bucket, prefix, marker, page.nextKey(), delimiter, maxKeys,
                        page.truncated(), entries, cpEntries);
            }
            return HttpResponse.ok(xml).contentType(MediaType.APPLICATION_XML);

        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("ListObjects '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> listMultipartUploads(
            final String bucket,
            final String prefix,
            final String delimiter,
            final String keyMarker,
            final String uploadIdMarker,
            final int maxUploads) {
        try {
            final MultipartService.ListUploadsPage page = multipartService.listMultipartUploads(
                    bucket,
                    prefix.isEmpty() ? null : prefix,
                    delimiter.isEmpty() ? null : delimiter,
                    keyMarker.isEmpty() ? null : keyMarker,
                    uploadIdMarker.isEmpty() ? null : uploadIdMarker,
                    maxUploads);

            final var uploads = page.uploads().stream()
                    .map(u -> new S3Xml.UploadEntry(
                            u.key(), u.uploadId(), u.owner(), "STANDARD", u.initiated()))
                    .toList();

            final String xml = S3Xml.listMultipartUploads(
                    bucket,
                    prefix.isEmpty() ? null : prefix,
                    delimiter.isEmpty() ? null : delimiter,
                    keyMarker.isEmpty() ? null : keyMarker,
                    uploadIdMarker.isEmpty() ? null : uploadIdMarker,
                    maxUploads,
                    page.truncated(),
                    page.nextKeyMarker(),
                    page.nextUploadIdMarker(),
                    uploads,
                    page.commonPrefixes());
            return HttpResponse.ok(xml).contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("ListMultipartUploads '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> getBucketLocation(final String bucket) {
        try {
            final String region = bucketService.getBucketLocation(bucket);
            return HttpResponse.ok(S3Xml.locationConstraint(region))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    // -------------------------------------------------------------------------
    // POST /<bucket>?delete — DeleteObjects
    // -------------------------------------------------------------------------

    @Post
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> postBucket(
            @PathVariable final String bucket,
            @Body final byte[] body,
            final HttpRequest<?> request) {

        if (!request.getParameters().contains("delete")) {
            return s3Error(S3Xml.error("MethodNotAllowed",
                    "The specified method is not allowed against this resource.", null), 405);
        }

        try {
            final DeleteObjectsRequest deleteReq = DeleteObjectsRequest.parse(body);
            final ObjectService.DeleteResult result =
                    objectService.deleteObjects(bucket, deleteReq.keys());

            final List<S3Xml.DeletedEntry> deleted = result.deleted().stream()
                    .map(S3Xml.DeletedEntry::new).toList();
            final List<S3Xml.DeleteErrorEntry> errors = result.errors().stream()
                    .map(e -> new S3Xml.DeleteErrorEntry(e.key(), e.code(), e.message()))
                    .toList();

            if (deleteReq.quiet() && errors.isEmpty()) {
                return HttpResponse.<String>ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .body(S3Xml.deleteResult(List.of(), List.of()));
            }
            return HttpResponse.ok(S3Xml.deleteResult(deleted, errors))
                    .contentType(MediaType.APPLICATION_XML);

        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final Exception ex) {
            LOG.error("DeleteObjects '{}' failed", bucket, ex);
            return internalError();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Parses the optional CreateBucket body to extract LocationConstraint. */
    private static String parseLocationConstraint(final byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            final var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final var doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(body));
            final var nodes = doc.getElementsByTagName("LocationConstraint");
            if (nodes.getLength() > 0) {
                final String val = nodes.item(0).getTextContent().trim();
                return val.isEmpty() ? null : val;
            }
        } catch (final Exception ex) {
            // Malformed body — treat as no location constraint
        }
        return null;
    }

    static <T> HttpResponse<T> s3Error(final S3Exception ex) {
        return s3Error(S3Xml.error(ex.getCode(), ex.getMessage(), null), ex.getHttpStatus());
    }

    @SuppressWarnings("unchecked")
    static <T> HttpResponse<T> s3Error(final String xml, final int status) {
        return (HttpResponse<T>) HttpResponse.status(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }

    static <T> HttpResponse<T> internalError() {
        return s3Error(S3Xml.error("InternalError",
                "We encountered an internal error. Please try again.", null), 500);
    }
}
