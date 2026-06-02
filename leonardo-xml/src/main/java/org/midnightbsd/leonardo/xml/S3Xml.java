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
package org.midnightbsd.leonardo.xml;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Generates S3-compatible XML response bodies. Every method returns a fully
 * formed XML string including the XML declaration, ready to send as
 * {@code application/xml}.
 *
 * <p>Character data is XML-escaped via {@link #esc(String)}.
 */
public final class S3Xml {

    private static final String PREAMBLE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    private static final String NS = " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"";
    static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private S3Xml() {}

    // -------------------------------------------------------------------------
    // Error
    // -------------------------------------------------------------------------

    public static String error(final String code, final String message) {
        return PREAMBLE + "<Error>" + t("Code", code) + t("Message", message) + "</Error>";
    }

    public static String error(final String code, final String message, final String resource) {
        return PREAMBLE + "<Error>" + t("Code", code) + t("Message", message)
                + t("Resource", resource) + "</Error>";
    }

    // -------------------------------------------------------------------------
    // ListBuckets
    // -------------------------------------------------------------------------

    public record BucketEntry(String name, Instant creationDate) {}

    public static String listBuckets(final String ownerId, final List<BucketEntry> buckets) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<ListAllMyBucketsResult").append(NS).append(">");
        sb.append("<Owner>").append(t("ID", ownerId)).append(t("DisplayName", ownerId))
                .append("</Owner>");
        sb.append("<Buckets>");
        for (final var b : buckets) {
            sb.append("<Bucket>").append(t("Name", b.name()))
                    .append(t("CreationDate", ISO.format(b.creationDate())))
                    .append("</Bucket>");
        }
        sb.append("</Buckets></ListAllMyBucketsResult>");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // ListObjectsV2
    // -------------------------------------------------------------------------

    public record ObjectEntry(String key, Instant lastModified, String etag, long size,
                              String storageClass) {}

    public record CommonPrefixEntry(String prefix) {}

    public static String listObjectsV2(
            final String bucket,
            final String prefix,
            final String delimiter,
            final int maxKeys,
            final boolean truncated,
            final String continuationToken,
            final String nextContinuationToken,
            final int keyCount,
            final List<ObjectEntry> objects,
            final List<CommonPrefixEntry> commonPrefixes) {

        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<ListBucketResult").append(NS).append(">");
        sb.append(t("Name", bucket));
        sb.append(t("Prefix", prefix != null ? prefix : ""));
        sb.append(t("KeyCount", String.valueOf(keyCount)));
        sb.append(t("MaxKeys", String.valueOf(maxKeys)));
        sb.append(t("IsTruncated", String.valueOf(truncated)));
        if (delimiter != null && !delimiter.isEmpty()) {
            sb.append(t("Delimiter", delimiter));
        }
        if (continuationToken != null && !continuationToken.isEmpty()) {
            sb.append(t("ContinuationToken", continuationToken));
        }
        if (nextContinuationToken != null && !nextContinuationToken.isEmpty()) {
            sb.append(t("NextContinuationToken", nextContinuationToken));
        }
        appendObjectEntries(sb, objects);
        appendCommonPrefixes(sb, commonPrefixes);
        sb.append("</ListBucketResult>");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // ListObjects (v1)
    // -------------------------------------------------------------------------

    public static String listObjects(
            final String bucket,
            final String prefix,
            final String marker,
            final String nextMarker,
            final String delimiter,
            final int maxKeys,
            final boolean truncated,
            final List<ObjectEntry> objects,
            final List<CommonPrefixEntry> commonPrefixes) {

        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<ListBucketResult").append(NS).append(">");
        sb.append(t("Name", bucket));
        sb.append(t("Prefix", prefix != null ? prefix : ""));
        sb.append(t("Marker", marker != null ? marker : ""));
        if (truncated && nextMarker != null) {
            sb.append(t("NextMarker", nextMarker));
        }
        sb.append(t("MaxKeys", String.valueOf(maxKeys)));
        sb.append(t("IsTruncated", String.valueOf(truncated)));
        if (delimiter != null && !delimiter.isEmpty()) {
            sb.append(t("Delimiter", delimiter));
        }
        appendObjectEntries(sb, objects);
        appendCommonPrefixes(sb, commonPrefixes);
        sb.append("</ListBucketResult>");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // DeleteObjects result
    // -------------------------------------------------------------------------

    public record DeletedEntry(String key) {}

    public record DeleteErrorEntry(String key, String code, String message) {}

    public static String deleteResult(
            final List<DeletedEntry> deleted,
            final List<DeleteErrorEntry> errors) {

        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<DeleteResult").append(NS).append(">");
        for (final var d : deleted) {
            sb.append("<Deleted>").append(t("Key", d.key())).append("</Deleted>");
        }
        for (final var e : errors) {
            sb.append("<Error>").append(t("Key", e.key())).append(t("Code", e.code()))
                    .append(t("Message", e.message())).append("</Error>");
        }
        sb.append("</DeleteResult>");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // GetBucketLocation
    // -------------------------------------------------------------------------

    public static String locationConstraint(final String region) {
        if (region == null || region.isEmpty() || "us-east-1".equals(region)) {
            return PREAMBLE + "<LocationConstraint" + NS + "/>";
        }
        return PREAMBLE + "<LocationConstraint" + NS + ">" + esc(region) + "</LocationConstraint>";
    }

    // -------------------------------------------------------------------------
    // CopyObject result
    // -------------------------------------------------------------------------

    public static String copyObjectResult(final String etag, final Instant lastModified) {
        return PREAMBLE + "<CopyObjectResult" + NS + ">"
                + t("ETag", etag)
                + t("LastModified", ISO.format(lastModified))
                + "</CopyObjectResult>";
    }

    // -------------------------------------------------------------------------
    // Phase 3 — bucket configuration GET responses
    // -------------------------------------------------------------------------

    public record GrantEntry(String grantee, String permission) {}

    /** Response body for GetBucketAcl. */
    public static String getBucketAcl(
            final String owner,
            final String canned,
            final List<GrantEntry> grants) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<AccessControlPolicy").append(NS).append(">");
        sb.append("<Owner>").append(t("ID", owner)).append(t("DisplayName", owner)).append("</Owner>");
        sb.append("<AccessControlList>");
        if (grants != null) {
            for (final var g : grants) {
                sb.append("<Grant>")
                        .append("<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
                        .append(" xsi:type=\"CanonicalUser\">")
                        .append(t("ID", g.grantee())).append(t("DisplayName", g.grantee()))
                        .append("</Grantee>")
                        .append(t("Permission", g.permission()))
                        .append("</Grant>");
            }
        } else if (canned != null) {
            // Represent canned ACL as a single owner FULL_CONTROL grant
            sb.append("<Grant>")
                    .append("<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
                    .append(" xsi:type=\"CanonicalUser\">")
                    .append(t("ID", owner)).append(t("DisplayName", owner))
                    .append("</Grantee>")
                    .append(t("Permission", "FULL_CONTROL"))
                    .append("</Grant>");
        }
        sb.append("</AccessControlList></AccessControlPolicy>");
        return sb.toString();
    }

    public record CorsRuleEntry(
            List<String> allowedOrigins,
            List<String> allowedMethods,
            List<String> allowedHeaders,
            List<String> exposeHeaders,
            Integer maxAgeSeconds) {}

    /** Response body for GetBucketCors. */
    public static String getBucketCors(final List<CorsRuleEntry> rules) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<CORSConfiguration").append(NS).append(">");
        for (final var r : rules) {
            sb.append("<CORSRule>");
            if (r.allowedOrigins() != null) r.allowedOrigins().forEach(v -> sb.append(t("AllowedOrigin", v)));
            if (r.allowedMethods() != null) r.allowedMethods().forEach(v -> sb.append(t("AllowedMethod", v)));
            if (r.allowedHeaders() != null) r.allowedHeaders().forEach(v -> sb.append(t("AllowedHeader", v)));
            if (r.exposeHeaders()  != null) r.exposeHeaders().forEach(v -> sb.append(t("ExposeHeader", v)));
            if (r.maxAgeSeconds() != null) sb.append(t("MaxAgeSeconds", String.valueOf(r.maxAgeSeconds())));
            sb.append("</CORSRule>");
        }
        sb.append("</CORSConfiguration>");
        return sb.toString();
    }

    /** Response body for GetBucketTagging. */
    public static String getBucketTagging(final Map<String, String> tags) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<Tagging").append(NS).append("><TagSet>");
        if (tags != null) {
            tags.forEach((k, v) ->
                    sb.append("<Tag>").append(t("Key", k)).append(t("Value", v)).append("</Tag>"));
        }
        sb.append("</TagSet></Tagging>");
        return sb.toString();
    }

    /**
     * Response body for GetBucketVersioning.
     *
     * @param status   "Enabled" | "Suspended" | null (null → empty element, versioning never set)
     * @param mfaDelete true if MFA delete is required
     */
    public static String getBucketVersioning(final String status, final boolean mfaDelete) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<VersioningConfiguration").append(NS).append(">");
        if (status != null && !status.isEmpty()) {
            sb.append(t("Status", status));
            sb.append(t("MfaDelete", mfaDelete ? "Enabled" : "Disabled"));
        }
        sb.append("</VersioningConfiguration>");
        return sb.toString();
    }

    /** Response body for GetBucketWebsite. */
    public static String getBucketWebsite(
            final String indexDocument, final String errorDocument) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<WebsiteConfiguration").append(NS).append(">");
        if (indexDocument != null && !indexDocument.isEmpty()) {
            sb.append("<IndexDocument>").append(t("Suffix", indexDocument)).append("</IndexDocument>");
        }
        if (errorDocument != null && !errorDocument.isEmpty()) {
            sb.append("<ErrorDocument>").append(t("Key", errorDocument)).append("</ErrorDocument>");
        }
        sb.append("</WebsiteConfiguration>");
        return sb.toString();
    }

    public record LifecycleRuleEntry(
            String id, String status, String prefix, Integer expirationDays) {}

    /** Response body for GetBucketLifecycleConfiguration. */
    public static String getBucketLifecycle(final List<LifecycleRuleEntry> rules) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<LifecycleConfiguration").append(NS).append(">");
        for (final var r : rules) {
            sb.append("<Rule>");
            if (r.id()     != null) sb.append(t("ID",     r.id()));
            if (r.status() != null) sb.append(t("Status", r.status()));
            final String pfx = r.prefix() != null ? r.prefix() : "";
            sb.append("<Filter>").append(t("Prefix", pfx)).append("</Filter>");
            if (r.expirationDays() != null) {
                sb.append("<Expiration>")
                        .append(t("Days", String.valueOf(r.expirationDays())))
                        .append("</Expiration>");
            }
            sb.append("</Rule>");
        }
        sb.append("</LifecycleConfiguration>");
        return sb.toString();
    }

    /** Response body for GetBucketPolicyStatus. */
    public static String getBucketPolicyStatus(final boolean isPublic) {
        return PREAMBLE + "<PolicyStatus" + NS + ">"
                + t("IsPublic", String.valueOf(isPublic)) + "</PolicyStatus>";
    }

    /** Response body for GetPublicAccessBlock. */
    public static String getPublicAccessBlock(
            final boolean blockPublicAcls,
            final boolean ignorePublicAcls,
            final boolean blockPublicPolicy,
            final boolean restrictPublicBuckets) {
        return PREAMBLE + "<PublicAccessBlockConfiguration" + NS + ">"
                + t("BlockPublicAcls",       String.valueOf(blockPublicAcls))
                + t("IgnorePublicAcls",      String.valueOf(ignorePublicAcls))
                + t("BlockPublicPolicy",     String.valueOf(blockPublicPolicy))
                + t("RestrictPublicBuckets", String.valueOf(restrictPublicBuckets))
                + "</PublicAccessBlockConfiguration>";
    }

    /** Response body for GetBucketRequestPayment. */
    public static String getBucketRequestPayment(final String payer) {
        return PREAMBLE + "<RequestPaymentConfiguration" + NS + ">"
                + t("Payer", payer != null ? payer : "BucketOwner")
                + "</RequestPaymentConfiguration>";
    }

    // -------------------------------------------------------------------------
    // Phase 6 — notifications, logging, replication GET responses
    // -------------------------------------------------------------------------

    /**
     * Response body for GetBucketNotificationConfiguration.
     * Returns stored XML verbatim, or an empty configuration element if none is set.
     */
    public static String getBucketNotificationConfiguration(final String storedXml) {
        if (storedXml != null && !storedXml.isBlank()) {
            return storedXml;
        }
        return PREAMBLE + "<NotificationConfiguration" + NS + "/>";
    }

    /** Response body for GetBucketLogging. */
    public static String getBucketLogging(final String targetBucket, final String targetPrefix) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<BucketLoggingStatus").append(NS).append(">");
        if (targetBucket != null && !targetBucket.isBlank()) {
            sb.append("<LoggingEnabled>");
            sb.append(t("TargetBucket", targetBucket));
            sb.append(t("TargetPrefix", targetPrefix != null ? targetPrefix : ""));
            sb.append("<TargetGrants/>");
            sb.append("</LoggingEnabled>");
        }
        sb.append("</BucketLoggingStatus>");
        return sb.toString();
    }

    /**
     * Response body for GetBucketReplication.
     * Returns stored XML verbatim; callers should 404 if null.
     */
    public static String getBucketReplication(final String storedXml) {
        return storedXml != null ? storedXml
                : PREAMBLE + "<ReplicationConfiguration" + NS + "/>";
    }

    // -------------------------------------------------------------------------
    // Phase 7 — encryption, ownership controls, accelerate GET responses
    // -------------------------------------------------------------------------

    /**
     * Response body for GetBucketEncryption.
     * Returns stored XML verbatim; callers should 404 if null.
     */
    public static String getBucketEncryption(final String storedXml) {
        return storedXml != null ? storedXml
                : PREAMBLE + "<ServerSideEncryptionConfiguration" + NS + "/>";
    }

    /**
     * Response body for GetBucketOwnershipControls.
     * Returns stored XML verbatim; callers should 404 if null.
     */
    public static String getBucketOwnershipControls(final String storedXml) {
        return storedXml != null ? storedXml
                : PREAMBLE + "<OwnershipControls" + NS + "/>";
    }

    /** Response body for GetBucketAccelerateConfiguration. */
    public static String getBucketAccelerateConfiguration(final String status) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<AccelerateConfiguration").append(NS).append(">");
        if (status != null && !status.isBlank()) {
            sb.append(t("Status", status));
        }
        sb.append("</AccelerateConfiguration>");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Phase 4 — object configuration GET responses
    // -------------------------------------------------------------------------

    /** Response body for GetObjectAcl — same structure as GetBucketAcl. */
    public static String getObjectAcl(
            final String owner,
            final String canned,
            final List<GrantEntry> grants) {
        return getBucketAcl(owner, canned, grants);
    }

    /** Response body for GetObjectTagging — same structure as GetBucketTagging. */
    public static String getObjectTagging(final Map<String, String> tags) {
        return getBucketTagging(tags);
    }

    public record VersionListEntry(
            String key,
            String versionId,
            String etag,
            long size,
            boolean deleteMarker,
            boolean isLatest,
            Instant lastModified,
            String storageClass) {}

    /** Response body for ListObjectVersions. */
    public static String listObjectVersions(
            final String bucket,
            final String prefix,
            final String delimiter,
            final int maxKeys,
            final String keyMarker,
            final String versionIdMarker,
            final boolean truncated,
            final String nextKeyMarker,
            final String nextVersionIdMarker,
            final String owner,
            final List<VersionListEntry> versions,
            final List<String> commonPrefixes) {

        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<ListVersionsResult").append(NS).append(">");
        sb.append(t("Name", bucket));
        sb.append(t("Prefix",         prefix         != null ? prefix         : ""));
        sb.append(t("KeyMarker",      keyMarker      != null ? keyMarker      : ""));
        sb.append(t("VersionIdMarker",versionIdMarker != null ? versionIdMarker : ""));
        sb.append(t("MaxKeys",        String.valueOf(maxKeys)));
        sb.append(t("IsTruncated",    String.valueOf(truncated)));
        if (delimiter != null && !delimiter.isEmpty()) sb.append(t("Delimiter", delimiter));
        if (truncated) {
            if (nextKeyMarker       != null) sb.append(t("NextKeyMarker",       nextKeyMarker));
            if (nextVersionIdMarker != null) sb.append(t("NextVersionIdMarker", nextVersionIdMarker));
        }
        for (final VersionListEntry v : versions) {
            final String tag = v.deleteMarker() ? "DeleteMarker" : "Version";
            sb.append("<").append(tag).append(">");
            sb.append(t("Key", v.key()));
            if (v.versionId() != null) sb.append(t("VersionId", v.versionId()));
            sb.append(t("IsLatest",     String.valueOf(v.isLatest())));
            sb.append(t("LastModified", ISO.format(v.lastModified())));
            if (!v.deleteMarker()) {
                sb.append(t("ETag",         v.etag()));
                sb.append(t("Size",         String.valueOf(v.size())));
                sb.append(t("StorageClass", v.storageClass() != null ? v.storageClass() : "STANDARD"));
            }
            sb.append("<Owner>").append(t("ID", owner)).append(t("DisplayName", owner)).append("</Owner>");
            sb.append("</").append(tag).append(">");
        }
        for (final String cp : commonPrefixes) {
            sb.append("<CommonPrefixes>").append(t("Prefix", cp)).append("</CommonPrefixes>");
        }
        sb.append("</ListVersionsResult>");
        return sb.toString();
    }

    /** Response body for GetObjectLockConfiguration. */
    public static String getObjectLockConfiguration(
            final boolean enabled,
            final String defaultRetentionMode,
            final Integer defaultRetentionDays,
            final Integer defaultRetentionYears) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<ObjectLockConfiguration").append(NS).append(">");
        sb.append(t("ObjectLockEnabled", enabled ? "Enabled" : "Disabled"));
        if (defaultRetentionMode != null) {
            sb.append("<Rule><DefaultRetention>");
            sb.append(t("Mode", defaultRetentionMode));
            if (defaultRetentionDays  != null) sb.append(t("Days",  String.valueOf(defaultRetentionDays)));
            if (defaultRetentionYears != null) sb.append(t("Years", String.valueOf(defaultRetentionYears)));
            sb.append("</DefaultRetention></Rule>");
        }
        sb.append("</ObjectLockConfiguration>");
        return sb.toString();
    }

    /** Response body for GetObjectRetention. */
    public static String getObjectRetention(final String mode, final Instant retainUntilDate) {
        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<Retention").append(NS).append(">");
        if (mode != null) sb.append(t("Mode", mode));
        if (retainUntilDate != null) sb.append(t("RetainUntilDate", ISO.format(retainUntilDate)));
        sb.append("</Retention>");
        return sb.toString();
    }

    /** Response body for GetObjectLegalHold. */
    public static String getObjectLegalHold(final boolean legalHold) {
        return PREAMBLE + "<LegalHold" + NS + ">"
                + t("Status", legalHold ? "ON" : "OFF")
                + "</LegalHold>";
    }

    // -------------------------------------------------------------------------
    // Multipart upload operations
    // -------------------------------------------------------------------------

    public record UploadEntry(String key, String uploadId, String owner,
                              String storageClass, Instant initiated) {}

    public record PartEntry(int partNumber, Instant lastModified, String etag, long size) {}

    /** Response body for CreateMultipartUpload ({@code POST /<bucket>/<key>?uploads}). */
    public static String initiateMultipartUploadResult(
            final String bucket, final String key, final String uploadId) {
        return PREAMBLE + "<InitiateMultipartUploadResult" + NS + ">"
                + t("Bucket", bucket) + t("Key", key) + t("UploadId", uploadId)
                + "</InitiateMultipartUploadResult>";
    }

    /** Response body for CompleteMultipartUpload ({@code POST /<bucket>/<key>?uploadId=...}). */
    public static String completeMultipartUploadResult(
            final String location,
            final String bucket,
            final String key,
            final String etag) {
        return PREAMBLE + "<CompleteMultipartUploadResult" + NS + ">"
                + t("Location", location)
                + t("Bucket", bucket)
                + t("Key", key)
                + t("ETag", etag)
                + "</CompleteMultipartUploadResult>";
    }

    /** Response body for ListMultipartUploads ({@code GET /<bucket>?uploads}). */
    public static String listMultipartUploads(
            final String bucket,
            final String prefix,
            final String delimiter,
            final String keyMarker,
            final String uploadIdMarker,
            final int maxUploads,
            final boolean truncated,
            final String nextKeyMarker,
            final String nextUploadIdMarker,
            final List<UploadEntry> uploads,
            final List<String> commonPrefixes) {

        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<ListMultipartUploadsResult").append(NS).append(">");
        sb.append(t("Bucket", bucket));
        sb.append(t("KeyMarker",      keyMarker      != null ? keyMarker      : ""));
        sb.append(t("UploadIdMarker", uploadIdMarker != null ? uploadIdMarker : ""));
        sb.append(t("MaxUploads", String.valueOf(maxUploads)));
        if (delimiter != null && !delimiter.isEmpty()) sb.append(t("Delimiter", delimiter));
        if (prefix    != null && !prefix.isEmpty())    sb.append(t("Prefix",    prefix));
        sb.append(t("IsTruncated", String.valueOf(truncated)));
        if (truncated) {
            if (nextKeyMarker    != null) sb.append(t("NextKeyMarker",    nextKeyMarker));
            if (nextUploadIdMarker != null) sb.append(t("NextUploadIdMarker", nextUploadIdMarker));
        }
        for (final var u : uploads) {
            sb.append("<Upload>")
                    .append(t("Key",          u.key()))
                    .append(t("UploadId",     u.uploadId()))
                    .append("<Initiator>").append(t("ID", u.owner()))
                            .append(t("DisplayName", u.owner())).append("</Initiator>")
                    .append("<Owner>").append(t("ID", u.owner()))
                            .append(t("DisplayName", u.owner())).append("</Owner>")
                    .append(t("StorageClass", u.storageClass() != null ? u.storageClass() : "STANDARD"))
                    .append(t("Initiated",    ISO.format(u.initiated())))
                    .append("</Upload>");
        }
        for (final String cp : commonPrefixes) {
            sb.append("<CommonPrefixes>").append(t("Prefix", cp)).append("</CommonPrefixes>");
        }
        sb.append("</ListMultipartUploadsResult>");
        return sb.toString();
    }

    /** Response body for ListParts ({@code GET /<bucket>/<key>?uploadId=...}). */
    public static String listParts(
            final String bucket,
            final String key,
            final String uploadId,
            final int partNumberMarker,
            final int maxParts,
            final boolean truncated,
            final int nextPartNumberMarker,
            final String owner,
            final String storageClass,
            final Instant initiated,
            final List<PartEntry> parts) {

        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<ListPartsResult").append(NS).append(">");
        sb.append(t("Bucket",            bucket));
        sb.append(t("Key",               key));
        sb.append(t("UploadId",          uploadId));
        sb.append(t("PartNumberMarker",  String.valueOf(partNumberMarker)));
        sb.append(t("MaxParts",          String.valueOf(maxParts)));
        sb.append(t("IsTruncated",       String.valueOf(truncated)));
        if (truncated) sb.append(t("NextPartNumberMarker", String.valueOf(nextPartNumberMarker)));
        sb.append("<Initiator>").append(t("ID", owner)).append(t("DisplayName", owner)).append("</Initiator>");
        sb.append("<Owner>").append(t("ID", owner)).append(t("DisplayName", owner)).append("</Owner>");
        sb.append(t("StorageClass", storageClass != null ? storageClass : "STANDARD"));
        if (initiated != null) sb.append(t("Initiated", ISO.format(initiated)));
        for (final var p : parts) {
            sb.append("<Part>")
                    .append(t("PartNumber",   String.valueOf(p.partNumber())))
                    .append(t("LastModified", ISO.format(p.lastModified())))
                    .append(t("ETag",         "\"" + p.etag() + "\""))
                    .append(t("Size",         String.valueOf(p.size())))
                    .append("</Part>");
        }
        sb.append("</ListPartsResult>");
        return sb.toString();
    }

    /** Response body for GetObjectAttributes ({@code GET /<bucket>/<key>} with attributes header). */
    public static String getObjectAttributesResponse(
            final String etag,
            final long size,
            final String storageClass,
            final Integer partsCount,
            final Map<String, String> checksums) {

        final var sb = new StringBuilder(PREAMBLE);
        sb.append("<GetObjectAttributesResponse").append(NS).append(">");
        if (etag        != null) sb.append(t("ETag",         etag));
        if (storageClass != null) sb.append(t("StorageClass", storageClass));
        sb.append(t("ObjectSize", String.valueOf(size)));
        if (checksums != null && !checksums.isEmpty()) {
            sb.append("<Checksum>");
            checksums.forEach((alg, val) -> sb.append(t(checksumTag(alg), val)));
            sb.append("</Checksum>");
        }
        if (partsCount != null) {
            sb.append("<ObjectParts>")
                    .append(t("PartsCount", String.valueOf(partsCount)))
                    .append("</ObjectParts>");
        }
        sb.append("</GetObjectAttributesResponse>");
        return sb.toString();
    }

    private static String checksumTag(final String algorithm) {
        return switch (algorithm.toLowerCase()) {
            case "crc32c"  -> "ChecksumCRC32C";
            case "crc32"   -> "ChecksumCRC32";
            case "sha256"  -> "ChecksumSHA256";
            case "sha1"    -> "ChecksumSHA1";
            default        -> "Checksum" + algorithm.toUpperCase();
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Encodes a continuation token as URL-safe Base64. */
    public static String encodeContinuationToken(final String key) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Decodes a continuation token. Returns {@code null} on invalid input. */
    public static String decodeContinuationToken(final String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(token),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    private static void appendObjectEntries(
            final StringBuilder sb, final List<ObjectEntry> objects) {
        for (final var o : objects) {
            sb.append("<Contents>")
                    .append(t("Key", o.key()))
                    .append(t("LastModified", ISO.format(o.lastModified())))
                    .append(t("ETag", o.etag()))
                    .append(t("Size", String.valueOf(o.size())))
                    .append(t("StorageClass", o.storageClass() != null ? o.storageClass() : "STANDARD"))
                    .append("</Contents>");
        }
    }

    private static void appendCommonPrefixes(
            final StringBuilder sb, final List<CommonPrefixEntry> prefixes) {
        for (final var cp : prefixes) {
            sb.append("<CommonPrefixes>").append(t("Prefix", cp.prefix()))
                    .append("</CommonPrefixes>");
        }
    }

    private static String t(final String tag, final String value) {
        return "<" + tag + ">" + esc(value) + "</" + tag + ">";
    }

    static String esc(final String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
