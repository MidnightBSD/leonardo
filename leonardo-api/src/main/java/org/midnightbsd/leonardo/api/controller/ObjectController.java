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
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import org.midnightbsd.leonardo.core.S3Exception;
import org.midnightbsd.leonardo.core.object.ObjectMetadata;
import org.midnightbsd.leonardo.core.object.ObjectService;
import org.midnightbsd.leonardo.xml.S3Xml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * S3 object-level endpoints ({@code /<bucket>/<key+>}):
 * <ul>
 *   <li>{@code HEAD} — HeadObject</li>
 *   <li>{@code GET} — GetObject</li>
 *   <li>{@code PUT} — PutObject or CopyObject (if {@code x-amz-copy-source} present)</li>
 *   <li>{@code DELETE} — DeleteObject</li>
 * </ul>
 */
@Controller("/{bucket}/{+key}")
public final class ObjectController {

    static final String CALLER_OBJECT_ID_HEADER = "x-leonardo-object-id";

    private static final Logger LOG = LoggerFactory.getLogger(ObjectController.class);
    private static final DateTimeFormatter RFC1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final ObjectService objectService;

    public ObjectController(final ObjectService objectService) {
        this.objectService = objectService;
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
    // GetObject
    // -------------------------------------------------------------------------

    @Get
    @Produces(MediaType.ALL)
    public HttpResponse<?> getObject(
            @PathVariable final String bucket,
            @PathVariable final String key) {
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

    // -------------------------------------------------------------------------
    // PutObject / CopyObject
    // -------------------------------------------------------------------------

    @Put
    @Consumes(MediaType.ALL)
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> putObject(
            @PathVariable final String bucket,
            @PathVariable final String key,
            @Header(value = "x-amz-copy-source", defaultValue = "") final String copySource,
            @Header(value = CALLER_OBJECT_ID_HEADER, defaultValue = "") final String callerObjectId,
            @Header(value = "content-type", defaultValue = "") final String contentType,
            @Body final byte[] body,
            final HttpRequest<?> request) {

        if (!copySource.isEmpty()) {
            return handleCopyObject(bucket, key, copySource);
        }

        // Collect x-amz-meta-* headers into userMetadata
        final var userMetadata = new HashMap<String, String>();
        request.getHeaders().forEachValue((name, value) -> {
            if (name.toLowerCase().startsWith("x-amz-meta-")) {
                userMetadata.put(name.toLowerCase(), value);
            }
        });

        try {
            final String etag = objectService.putObject(
                    bucket, key,
                    callerObjectId.isEmpty() ? null : callerObjectId,
                    contentType.isEmpty() ? null : contentType,
                    userMetadata.isEmpty() ? null : userMetadata,
                    body);
            return HttpResponse.<String>ok()
                    .header("ETag", "\"" + etag + "\"");
        } catch (final S3Exception ex) {
            return BucketController.s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("PutObject '{}/{}' failed", bucket, key, ex);
            return BucketController.internalError();
        }
    }

    private HttpResponse<String> handleCopyObject(
            final String dstBucket, final String dstKey, final String rawSource) {

        // x-amz-copy-source: /bucket/key  or  bucket/key (optionally URL-encoded)
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
    // DeleteObject
    // -------------------------------------------------------------------------

    @Delete
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> deleteObject(
            @PathVariable final String bucket,
            @PathVariable final String key) {
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
}
