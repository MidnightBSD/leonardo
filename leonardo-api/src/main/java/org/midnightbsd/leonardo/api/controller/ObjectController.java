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
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Head;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Put;

/**
 * S3 object-level endpoints: {@code /:bucket/:key+}.
 *
 * <p>The {@code key+} matches everything after the bucket name, including
 * slashes — S3 keys can contain {@code /} but they're not directory separators
 * on the wire, just part of the key.
 *
 * <p>Phase 1 targets: {@code PutObject}, {@code GetObject}, {@code HeadObject},
 * {@code DeleteObject}, {@code CopyObject}. Multipart is Phase 2 (M3).
 *
 * <p>Honors the {@code x-leonardo-object-id} header for caller-chosen on-disk
 * IDs (project plan §6) — validation and uniqueness checks happen in
 * {@code leonardo-core}.
 */
@Controller("/{bucket}/{+key}")
public final class ObjectController {

    public static final String CALLER_OBJECT_ID_HEADER = "x-leonardo-object-id";

    @Head
    public HttpResponse<?> headObject(
            @PathVariable final String bucket,
            @PathVariable final String key) {
        return HttpResponse.status(501);
    }

    @Get
    public HttpResponse<?> getObject(
            @PathVariable final String bucket,
            @PathVariable final String key) {
        // TODO(M2): stream the payload via Netty FileRegion / sendfile.
        return HttpResponse.status(501);
    }

    @Put
    public HttpResponse<?> putObject(
            @PathVariable final String bucket,
            @PathVariable final String key,
            @Header(value = CALLER_OBJECT_ID_HEADER, defaultValue = "") final String callerObjectId) {
        // TODO(M2): validate callerObjectId per the §6 regex, check bucket's
        // caller_object_ids policy, write payload + metadata atomically.
        return HttpResponse.status(501);
    }

    @Delete
    public HttpResponse<?> deleteObject(
            @PathVariable final String bucket,
            @PathVariable final String key) {
        return HttpResponse.status(501);
    }
}
