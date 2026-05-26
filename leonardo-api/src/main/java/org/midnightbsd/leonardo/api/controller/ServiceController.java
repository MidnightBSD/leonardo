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
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import org.midnightbsd.leonardo.core.S3Exception;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata;
import org.midnightbsd.leonardo.core.bucket.BucketService;
import org.midnightbsd.leonardo.xml.S3Xml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * S3 service-level endpoint ({@code GET /}): {@code ListBuckets}.
 */
@Controller("/")
public final class ServiceController {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceController.class);

    private final BucketService bucketService;

    public ServiceController(final BucketService bucketService) {
        this.bucketService = bucketService;
    }

    /**
     * S3 {@code ListBuckets}: returns all buckets.
     */
    @Get
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> listBuckets(final HttpRequest<?> request) {
        final String identity = request.getAttribute("s3.identity", String.class)
                .orElse("anonymous");
        try {
            final List<BucketMetadata> buckets = bucketService.listBuckets();
            final List<S3Xml.BucketEntry> entries = buckets.stream()
                    .map(b -> new S3Xml.BucketEntry(b.name(), b.createdAt()))
                    .toList();
            return HttpResponse.ok(S3Xml.listBuckets(identity, entries))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final IOException ex) {
            LOG.error("ListBuckets failed", ex);
            return s3Error(S3Xml.error("InternalError",
                    "We encountered an internal error. Please try again.", null), 500);
        }
    }

    private static HttpResponse<String> s3Error(final String xml, final int status) {
        return HttpResponse.<String>status(io.micronaut.http.HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }
}
