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
package org.midnightbsd.leonardo.core.bucket;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of a bucket's {@code bucket.yaml} file (§4 of the
 * project plan).
 *
 * <p>This is the full v1 schema. Fields that don't apply to a given bucket
 * (e.g. {@code policy} when none is configured) are nullable. The YAML
 * marshaller in {@code leonardo-yaml} handles serialization round-trips.
 */
public record BucketMetadata(
        String name,
        Instant createdAt,
        String owner,
        String region,

        VersioningConfig versioning,
        AclConfig acl,
        Map<String, String> tags,
        LifecycleConfig lifecycle,
        CorsConfig cors,
        WebsiteConfig website,
        EncryptionConfig encryption,
        ObjectLockConfig objectLock,
        String policyJson,                 // raw S3 bucket-policy JSON, or null

        // Leonardo-specific options (§9 of the plan):
        CallerObjectIdsMode callerObjectIds,
        FsyncMode fsyncOnWrite,
        Long pathQuotaBytes,               // null = no quota
        boolean immutable,
        String defaultContentType,         // null = no default
        RateLimitConfig rateLimit
) {
    public enum CallerObjectIdsMode {
        @JsonProperty("required") REQUIRED,
        @JsonProperty("allowed") ALLOWED,
        @JsonProperty("forbidden") FORBIDDEN
    }
    public enum FsyncMode {
        @JsonProperty("inherit") INHERIT,
        @JsonProperty("true") TRUE,
        @JsonProperty("false") FALSE
    }
    public enum VersioningStatus {
        @JsonProperty("Enabled") ENABLED,
        @JsonProperty("Suspended") SUSPENDED,
        @JsonProperty("Disabled") DISABLED
    }
    public enum RateLimitScope {
        @JsonProperty("per_identity") PER_IDENTITY,
        @JsonProperty("per_bucket") PER_BUCKET
    }

    public record VersioningConfig(VersioningStatus status, boolean mfaDelete) {}
    public record AclConfig(String canned, List<AclGrant> grants) {}
    public record AclGrant(String grantee, String permission) {}
    public record LifecycleConfig(List<LifecycleRule> rules) {}
    public record LifecycleRule(String id, String status, String prefix, Integer expirationDays) {}
    public record CorsConfig(List<CorsRule> rules) {}
    public record CorsRule(
            List<String> allowedOrigins,
            List<String> allowedMethods,
            List<String> allowedHeaders,
            List<String> exposeHeaders,
            Integer maxAgeSeconds) {}
    public record WebsiteConfig(boolean enabled, String indexDocument, String errorDocument) {}
    public record EncryptionConfig(String defaultAlgorithm) {}   // none | AES256
    public record ObjectLockConfig(boolean enabled) {}
    public record RateLimitConfig(
            Long requestsPerSecond,
            Long bytesPerSecond,
            int burstMultiplier,
            RateLimitScope scope) {}

    /**
     * Validates invariants from the project plan. Throws
     * {@link IllegalArgumentException} when the metadata is inconsistent.
     *
     * <p>Currently enforces the §9 immutable+versioning+object_lock rules.
     * Called from the YAML loader after deserialization and again before any
     * write, so an operator hand-editing {@code bucket.yaml} can't get the
     * server into an inconsistent state.
     */
    public void validateInvariants() {
        if (immutable) {
            if (objectLock != null && objectLock.enabled()) {
                throw new IllegalArgumentException(
                        "bucket '" + name + "': immutable=true is mutually exclusive with object_lock.enabled=true");
            }
            if (versioning != null && versioning.status() == VersioningStatus.ENABLED) {
                throw new IllegalArgumentException(
                        "bucket '" + name + "': immutable=true forces versioning=Disabled "
                                + "(version history is meaningless without overwrites or deletes)");
            }
        }
        if (rateLimit != null && rateLimit.burstMultiplier() < 1) {
            throw new IllegalArgumentException(
                    "bucket '" + name + "': rate_limit.burst_multiplier must be >= 1");
        }
    }
}
