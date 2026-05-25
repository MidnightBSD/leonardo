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
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

/**
 * S3 service-level endpoint: operations on the root URL with no bucket.
 *
 * <p>Currently implements only the listing path stub. Phase 1 work fills in
 * {@code ListBuckets} for real, wiring it to {@code BucketService}.
 */
@Controller("/")
public final class ServiceController {

    /**
     * S3 {@code ListBuckets}. Returns all buckets owned by the authenticated caller.
     *
     * <p>Skeleton — returns an empty S3 XML response for now so SDK smoke tests
     * (e.g. {@code aws s3 ls}) don't hard-fail during M0.
     */
    @Get
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> listBuckets() {
        final String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListAllMyBucketsResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Owner>
                        <ID>leonardo</ID>
                        <DisplayName>leonardo</DisplayName>
                    </Owner>
                    <Buckets/>
                </ListAllMyBucketsResult>
                """;
        return HttpResponse.ok(body);
    }
}
