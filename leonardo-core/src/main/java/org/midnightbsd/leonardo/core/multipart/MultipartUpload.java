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
package org.midnightbsd.leonardo.core.multipart;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * State of an in-progress multipart upload, persisted as one YAML file under
 * {@code buckets/<bucket>/_meta/uploads/<upload-id>.yaml}.
 *
 * <p>The {@code objectId} is a ULID reserved at {@code CreateMultipartUpload} time
 * so that {@code CompleteMultipartUpload} can assemble the final payload file at
 * a known, stable path.
 *
 * <p>{@code parts} is updated atomically after each successful {@code UploadPart}
 * or {@code UploadPartCopy} call.
 */
public record MultipartUpload(
        String uploadId,
        String bucket,
        String key,
        String objectId,
        String owner,
        String contentType,
        Instant initiated,
        Map<String, String> userMetadata,
        String callerObjectId,
        List<PartInfo> parts
) {

    /** Per-part metadata, written after each successful UploadPart/UploadPartCopy. */
    public record PartInfo(
            int partNumber,
            String etag,
            long size,
            Instant lastModified
    ) {}
}
