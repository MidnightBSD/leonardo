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
package org.midnightbsd.leonardo.core.object;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Per-object metadata, persisted as one YAML file per object under
 * {@code buckets/<bucket>/_meta/objects/<sha256-of-key>.yaml} (project plan §4).
 *
 * <p>The {@code objectId} is the on-disk filename of the payload — caller-chosen
 * (project plan §6) or server-generated (ULID). When versioning is enabled,
 * each entry in {@code versions} has its own {@code objectId} so the data files
 * stay distinct across history.
 */
public record ObjectMetadata(
        String key,
        String objectId,
        long size,
        String contentType,
        String etag,
        Instant createdAt,
        Instant lastModified,
        String storageClass,
        List<ObjectVersion> versions,
        Map<String, String> userMetadata,
        Map<String, String> tags,
        String aclCanned,
        boolean legalHold,
        RetentionConfig retention
) {

    public record ObjectVersion(
            String versionId,
            String objectId,
            long size,
            String etag,
            boolean deleteMarker,
            Instant createdAt) {}

    public record RetentionConfig(String mode, Instant retainUntilDate) {}

    /**
     * Regex governing caller-supplied object IDs (§6 of the plan).
     * Rejects {@code ..}, leading {@code .}, slashes, NULs, and anything else
     * that could escape {@code data/}.
     */
    public static final java.util.regex.Pattern CALLER_OBJECT_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$");

    /**
     * Validates a caller-supplied object ID against the §6 rules.
     *
     * @throws IllegalArgumentException when the value is malformed.
     */
    public static void validateCallerObjectId(final String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("object id must not be empty");
        }
        if (value.length() > 128) {
            throw new IllegalArgumentException("object id exceeds 128 chars: " + value.length());
        }
        if (!CALLER_OBJECT_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "object id contains disallowed characters or starts with '.': " + value);
        }
        if (value.contains("..")) {
            throw new IllegalArgumentException("object id contains '..': " + value);
        }
    }
}
