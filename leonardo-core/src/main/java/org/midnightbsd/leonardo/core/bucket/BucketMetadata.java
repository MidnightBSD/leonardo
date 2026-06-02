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
        RateLimitConfig rateLimit,
        PublicAccessBlockConfig publicAccessBlock,
        String requestPayer,              // "BucketOwner" | "Requester" | null

        // Phase 6 — notifications, logging, replication (stubs)
        LoggingConfig loggingConfig,
        String notificationConfigXml,     // raw XML from PutBucketNotificationConfiguration, or null
        String replicationConfigXml,      // raw XML from PutBucketReplication, or null

        // Phase 7 — encryption, ownership controls, accelerate (stubs)
        String sseConfigXml,              // raw XML from PutBucketEncryption, or null
        String ownershipControlsXml,      // raw XML from PutBucketOwnershipControls, or null
        String accelerateStatus,          // "Enabled" | "Suspended" | null

        // Phase 8 — analytics, metrics, inventory, intelligent-tiering, ABAC (stubs)
        Map<String, String> analyticsConfigurations,   // id → raw XML, or null
        Map<String, String> metricsConfigurations,     // id → raw XML, or null
        Map<String, String> inventoryConfigurations,   // id → raw XML, or null
        Map<String, String> intelligentTieringConfigs, // id → raw XML, or null
        String abacXml,                                 // raw XML from PutBucketAbac, or null

        // Phase 9 — metadata table, session, directory buckets (stubs)
        String metadataTableConfigXml                  // raw XML from CreateBucketMetadataTableConfiguration, or null
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
    public record ObjectLockConfig(
            boolean enabled,
            String defaultRetentionMode,   // GOVERNANCE | COMPLIANCE | null
            Integer defaultRetentionDays,
            Integer defaultRetentionYears) {}
    public record PublicAccessBlockConfig(
            boolean blockPublicAcls,
            boolean ignorePublicAcls,
            boolean blockPublicPolicy,
            boolean restrictPublicBuckets) {}
    public record RateLimitConfig(
            Long requestsPerSecond,
            Long bytesPerSecond,
            int burstMultiplier,
            RateLimitScope scope) {}

    public record LoggingConfig(
            String targetBucket,   // null → logging disabled
            String targetPrefix) {}

    // -------------------------------------------------------------------------
    // Copy helpers — each returns a new record with one field replaced.
    // -------------------------------------------------------------------------

    public BucketMetadata withAcl(final AclConfig newAcl) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, newAcl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withCors(final CorsConfig newCors) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, newCors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withTags(final Map<String, String> newTags) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, newTags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withVersioning(final VersioningConfig newVersioning) {
        return new BucketMetadata(name, createdAt, owner, region, newVersioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withWebsite(final WebsiteConfig newWebsite) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, newWebsite, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withLifecycle(final LifecycleConfig newLifecycle) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                newLifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withPolicyJson(final String newPolicyJson) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, newPolicyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withPublicAccessBlock(final PublicAccessBlockConfig newPab) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, newPab, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withRequestPayer(final String newRequestPayer) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, newRequestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withObjectLock(final ObjectLockConfig newObjectLock) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, newObjectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withLoggingConfig(final LoggingConfig newLogging) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                newLogging, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withNotificationConfigXml(final String newXml) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, newXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withReplicationConfigXml(final String newXml) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, newXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withSseConfigXml(final String newXml) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                newXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withOwnershipControlsXml(final String newXml) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, newXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withAccelerateStatus(final String newStatus) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, newStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withAnalyticsConfiguration(final String id, final String xml) {
        final Map<String, String> updated = buildUpdatedMap(analyticsConfigurations, id, xml);
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                updated, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withMetricsConfiguration(final String id, final String xml) {
        final Map<String, String> updated = buildUpdatedMap(metricsConfigurations, id, xml);
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, updated, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withInventoryConfiguration(final String id, final String xml) {
        final Map<String, String> updated = buildUpdatedMap(inventoryConfigurations, id, xml);
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, updated, intelligentTieringConfigs, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withIntelligentTieringConfiguration(final String id, final String xml) {
        final Map<String, String> updated = buildUpdatedMap(intelligentTieringConfigs, id, xml);
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, updated, abacXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withAbacXml(final String newXml) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, newXml,
                metadataTableConfigXml);
    }

    public BucketMetadata withMetadataTableConfigXml(final String newXml) {
        return new BucketMetadata(name, createdAt, owner, region, versioning, acl, tags,
                lifecycle, cors, website, encryption, objectLock, policyJson,
                callerObjectIds, fsyncOnWrite, pathQuotaBytes, immutable, defaultContentType,
                rateLimit, publicAccessBlock, requestPayer,
                loggingConfig, notificationConfigXml, replicationConfigXml,
                sseConfigXml, ownershipControlsXml, accelerateStatus,
                analyticsConfigurations, metricsConfigurations, inventoryConfigurations, intelligentTieringConfigs, abacXml,
                newXml);
    }

    private static Map<String, String> buildUpdatedMap(
            final Map<String, String> existing, final String id, final String xml) {
        if (xml == null) {
            if (existing == null || !existing.containsKey(id)) return existing;
            final var m = new java.util.HashMap<>(existing);
            m.remove(id);
            return m.isEmpty() ? null : java.util.Collections.unmodifiableMap(m);
        }
        final var m = new java.util.HashMap<String, String>(existing != null ? existing : Map.of());
        m.put(id, xml);
        return java.util.Collections.unmodifiableMap(m);
    }

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
