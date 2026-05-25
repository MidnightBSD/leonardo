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

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Head;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;

/**
 * S3 bucket-level endpoints: {@code /:bucket}, {@code /:bucket?location}, etc.
 *
 * <p>Phase 1 targets: {@code CreateBucket}, {@code DeleteBucket},
 * {@code HeadBucket}, {@code ListObjectsV2}, {@code GetBucketLocation}.
 * Everything below is a skeleton returning HTTP 501 — to be filled in by M2.
 */
@Controller("/{bucket}")
public final class BucketController {

    @Head
    public HttpResponse<?> headBucket(@PathVariable final String bucket) {
        // TODO(M2): check that bucket exists, return 404 NoSuchBucket if not.
        return HttpResponse.status(501);   // NotImplemented
    }

    @Put
    public HttpResponse<?> createBucket(@PathVariable final String bucket) {
        // TODO(M2): validate bucket name, create directory layout, write bucket.yaml.
        return HttpResponse.status(501);
    }

    @Delete
    public HttpResponse<?> deleteBucket(@PathVariable final String bucket) {
        // TODO(M2): check empty, refuse otherwise (S3's BucketNotEmpty).
        return HttpResponse.status(501);
    }

    /**
     * Routes {@code GET /:bucket} requests. S3 overloads this URL heavily based on
     * the query string: bare = ListObjectsV2; {@code ?location} = GetBucketLocation;
     * {@code ?acl} = GetBucketAcl; etc. The full dispatch lands in M2–M4.
     */
    @Get
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<?> getBucket(
            @PathVariable final String bucket,
            @QueryValue(value = "location", defaultValue = "") final String location,
            @QueryValue(value = "list-type", defaultValue = "") final String listType,
            @QueryValue(value = "prefix", defaultValue = "") final String prefix) {

        if (!location.isEmpty()) {
            // GetBucketLocation stub - returns the configured region.
            // M2 makes this read from bucket.yaml instead.
            final String body = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <LocationConstraint xmlns="http://s3.amazonaws.com/doc/2006-03-01/">us-east-1</LocationConstraint>
                    """;
            return HttpResponse.ok(body);
        }
        // ListObjectsV2 (and legacy ListObjects) stub.
        return HttpResponse.status(501);
    }
}
