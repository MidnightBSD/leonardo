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
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import org.midnightbsd.leonardo.core.S3Exception;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata;
import org.midnightbsd.leonardo.core.bucket.BucketService;
import org.midnightbsd.leonardo.core.multipart.MultipartService;
import org.midnightbsd.leonardo.core.object.ObjectMetadataStore;
import org.midnightbsd.leonardo.core.object.ObjectService;
import org.midnightbsd.leonardo.xml.DeleteObjectsRequest;
import org.midnightbsd.leonardo.xml.S3Xml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * S3 bucket-level endpoints:
 * <ul>
 *   <li>{@code HEAD /<bucket>} — HeadBucket</li>
 *   <li>{@code PUT /<bucket>} — CreateBucket; with sub-resource params: PutBucketAcl,
 *       PutBucketCors, PutBucketTagging, PutBucketVersioning, PutBucketWebsite,
 *       PutBucketLifecycleConfiguration, PutBucketPolicy, PutPublicAccessBlock,
 *       PutBucketRequestPayment</li>
 *   <li>{@code DELETE /<bucket>} — DeleteBucket; with sub-resource params:
 *       DeleteBucketCors, DeleteBucketTagging, DeleteBucketWebsite,
 *       DeleteBucketLifecycle, DeleteBucketPolicy, DeletePublicAccessBlock</li>
 *   <li>{@code GET /<bucket>} — ListObjectsV2/ListObjects, GetBucketLocation,
 *       ListMultipartUploads, GetBucketAcl, GetBucketCors, GetBucketTagging,
 *       GetBucketVersioning, GetBucketWebsite, GetBucketLifecycleConfiguration,
 *       GetBucketPolicy, GetBucketPolicyStatus, GetPublicAccessBlock,
 *       GetBucketRequestPayment</li>
 *   <li>{@code POST /<bucket>?delete} — DeleteObjects</li>
 * </ul>
 */
@Controller("/{bucket}")
public final class BucketController {

    private static final Logger LOG = LoggerFactory.getLogger(BucketController.class);

    private static final ObjectMapper POLICY_MAPPER = new ObjectMapper();

    private final BucketService bucketService;
    private final ObjectService objectService;
    private final MultipartService multipartService;

    public BucketController(
            final BucketService bucketService,
            final ObjectService objectService,
            final MultipartService multipartService) {
        this.bucketService    = bucketService;
        this.objectService    = objectService;
        this.multipartService = multipartService;
    }

    // -------------------------------------------------------------------------
    // PUT /<bucket> — CreateBucket + Phase 3 bucket configuration
    // -------------------------------------------------------------------------

    @Put
    @Consumes(MediaType.ALL)
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> putBucket(
            @PathVariable final String bucket,
            @Header(value = "x-amz-acl", defaultValue = "") final String cannedAcl,
            @Header(value = "x-amz-bucket-object-lock-enabled", defaultValue = "") final String objectLockHeader,
            @Body final Optional<String> rawBody,
            final HttpRequest<?> request) {

        final byte[] body = rawBody.map(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8)).orElse(null);
        if (request.getParameters().contains("acl")) {
            return putBucketAcl(bucket, body, cannedAcl);
        }
        if (request.getParameters().contains("cors")) {
            return putBucketCors(bucket, body);
        }
        if (request.getParameters().contains("tagging")) {
            return putBucketTagging(bucket, body);
        }
        if (request.getParameters().contains("versioning")) {
            return putBucketVersioning(bucket, body);
        }
        if (request.getParameters().contains("website")) {
            return putBucketWebsite(bucket, body);
        }
        if (request.getParameters().contains("lifecycle")) {
            return putBucketLifecycle(bucket, body);
        }
        if (request.getParameters().contains("policy")) {
            return putBucketPolicy(bucket, body);
        }
        if (request.getParameters().contains("publicAccessBlock")) {
            return putPublicAccessBlock(bucket, body);
        }
        if (request.getParameters().contains("requestPayment")) {
            return putBucketRequestPayment(bucket, body);
        }
        if (request.getParameters().contains("object-lock")) {
            return putObjectLockConfiguration(bucket, body);
        }

        // CreateBucket
        final String identity = request.getAttribute("s3.identity", String.class)
                .orElse("anonymous");
        final String locationConstraint = parseLocationConstraint(body);
        final boolean objectLockEnabled = "true".equalsIgnoreCase(objectLockHeader);
        try {
            bucketService.createBucket(bucket, identity, locationConstraint, objectLockEnabled);
            return HttpResponse.<String>ok()
                    .header("Location", "/" + bucket);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("CreateBucket '{}' failed", bucket, ex);
            return internalError();
        }
    }

    // -------------------------------------------------------------------------
    // DELETE /<bucket> — DeleteBucket + Phase 3 deletes
    // -------------------------------------------------------------------------

    @Delete
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> deleteBucket(
            @PathVariable final String bucket,
            final HttpRequest<?> request) {

        if (request.getParameters().contains("cors")) {
            return deleteBucketCors(bucket);
        }
        if (request.getParameters().contains("tagging")) {
            return deleteBucketTagging(bucket);
        }
        if (request.getParameters().contains("website")) {
            return deleteBucketWebsite(bucket);
        }
        if (request.getParameters().contains("lifecycle")) {
            return deleteBucketLifecycle(bucket);
        }
        if (request.getParameters().contains("policy")) {
            return deleteBucketPolicy(bucket);
        }
        if (request.getParameters().contains("publicAccessBlock")) {
            return deletePublicAccessBlock(bucket);
        }

        // DeleteBucket
        try {
            bucketService.deleteBucket(bucket);
            return HttpResponse.<String>status(HttpStatus.NO_CONTENT);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("DeleteBucket '{}' failed", bucket, ex);
            return internalError();
        }
    }

    // -------------------------------------------------------------------------
    // GET /<bucket> — ListObjectsV2 / ListObjects / GetBucketLocation /
    //                 ListMultipartUploads + Phase 3 GETs
    // -------------------------------------------------------------------------

    @Get
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> getBucket(
            @PathVariable final String bucket,
            @QueryValue(value = "list-type", defaultValue = "") final String listType,
            @QueryValue(value = "prefix", defaultValue = "") final String prefix,
            @QueryValue(value = "delimiter", defaultValue = "") final String delimiter,
            @QueryValue(value = "max-keys", defaultValue = "1000") final int maxKeys,
            @QueryValue(value = "continuation-token", defaultValue = "") final String continuationToken,
            @QueryValue(value = "start-after", defaultValue = "") final String startAfter,
            @QueryValue(value = "marker", defaultValue = "") final String marker,
            @QueryValue(value = "key-marker", defaultValue = "") final String keyMarker,
            @QueryValue(value = "upload-id-marker", defaultValue = "") final String uploadIdMarker,
            @QueryValue(value = "max-uploads", defaultValue = "1000") final int maxUploads,
            @QueryValue(value = "version-id-marker", defaultValue = "") final String versionIdMarker,
            final HttpRequest<?> request) {

        // HeadBucket: GET handler also serves HEAD (Micronaut strips response body)
        if (io.micronaut.http.HttpMethod.HEAD == request.getMethod()) {
            try {
                bucketService.headBucket(bucket);
                return HttpResponse.<String>ok();
            } catch (final S3Exception ex) {
                return s3Error(ex);
            }
        }

        // Sub-resource dispatching (query-param presence, no value needed)
        if (request.getParameters().contains("location")) {
            return getBucketLocation(bucket);
        }
        if (request.getParameters().contains("uploads")) {
            return listMultipartUploads(bucket, prefix, delimiter,
                    keyMarker, uploadIdMarker, maxUploads);
        }
        if (request.getParameters().contains("acl")) {
            return getBucketAcl(bucket);
        }
        if (request.getParameters().contains("cors")) {
            return getBucketCors(bucket);
        }
        if (request.getParameters().contains("tagging")) {
            return getBucketTagging(bucket);
        }
        if (request.getParameters().contains("versioning")) {
            return getBucketVersioning(bucket);
        }
        if (request.getParameters().contains("website")) {
            return getBucketWebsite(bucket);
        }
        if (request.getParameters().contains("lifecycle")) {
            return getBucketLifecycle(bucket);
        }
        if (request.getParameters().contains("policy")) {
            return getBucketPolicy(bucket);
        }
        if (request.getParameters().contains("policyStatus")) {
            return getBucketPolicyStatus(bucket);
        }
        if (request.getParameters().contains("publicAccessBlock")) {
            return getBucketPublicAccessBlock(bucket);
        }
        if (request.getParameters().contains("requestPayment")) {
            return getBucketRequestPayment(bucket);
        }
        if (request.getParameters().contains("versions")) {
            return listObjectVersions(bucket, prefix, delimiter, maxKeys,
                    keyMarker, versionIdMarker);
        }
        if (request.getParameters().contains("object-lock")) {
            return getObjectLockConfiguration(bucket);
        }

        // ListObjectsV2 (list-type=2) or ListObjects v1
        final boolean isV2 = "2".equals(listType);
        try {
            bucketService.headBucket(bucket);

            final String startAfterKey;
            if (isV2) {
                if (!continuationToken.isEmpty()) {
                    startAfterKey = S3Xml.decodeContinuationToken(continuationToken);
                } else {
                    startAfterKey = startAfter.isEmpty() ? null : startAfter;
                }
            } else {
                startAfterKey = marker.isEmpty() ? null : marker;
            }

            final org.midnightbsd.leonardo.core.object.ObjectMetadataStore.ListPage page =
                    objectService.listObjects(
                            bucket,
                            prefix.isEmpty() ? null : prefix,
                            delimiter.isEmpty() ? null : delimiter,
                            maxKeys,
                            startAfterKey);

            final List<S3Xml.ObjectEntry> entries = page.objects().stream()
                    .map(m -> new S3Xml.ObjectEntry(
                            m.key(), m.lastModified(), m.etag(), m.size(), m.storageClass()))
                    .toList();
            final List<S3Xml.CommonPrefixEntry> cpEntries = page.commonPrefixes().stream()
                    .map(S3Xml.CommonPrefixEntry::new)
                    .toList();

            final String xml;
            if (isV2) {
                final String nextToken = page.truncated() && page.nextKey() != null
                        ? S3Xml.encodeContinuationToken(page.nextKey()) : null;
                xml = S3Xml.listObjectsV2(
                        bucket, prefix, delimiter, maxKeys,
                        page.truncated(), continuationToken.isEmpty() ? null : continuationToken,
                        nextToken, entries.size(), entries, cpEntries);
            } else {
                xml = S3Xml.listObjects(
                        bucket, prefix, marker, page.nextKey(), delimiter, maxKeys,
                        page.truncated(), entries, cpEntries);
            }
            return HttpResponse.ok(xml).contentType(MediaType.APPLICATION_XML);

        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("ListObjects '{}' failed", bucket, ex);
            return internalError();
        }
    }

    // -------------------------------------------------------------------------
    // POST /<bucket>?delete — DeleteObjects
    // -------------------------------------------------------------------------

    @Post
    @Consumes(MediaType.ALL)
    @Produces(MediaType.APPLICATION_XML)
    public HttpResponse<String> postBucket(
            @PathVariable final String bucket,
            @Body final Optional<byte[]> body,
            final HttpRequest<?> request) {

        final byte[] xmlBody = body.orElse(null);
        if (!request.getParameters().contains("delete")) {
            return s3Error(S3Xml.error("MethodNotAllowed",
                    "The specified method is not allowed against this resource.", null), 405);
        }

        try {
            final DeleteObjectsRequest deleteReq = DeleteObjectsRequest.parse(xmlBody);
            final ObjectService.DeleteResult result =
                    objectService.deleteObjects(bucket, deleteReq.keys());

            final List<S3Xml.DeletedEntry> deleted = result.deleted().stream()
                    .map(S3Xml.DeletedEntry::new).toList();
            final List<S3Xml.DeleteErrorEntry> errors = result.errors().stream()
                    .map(e -> new S3Xml.DeleteErrorEntry(e.key(), e.code(), e.message()))
                    .toList();

            if (deleteReq.quiet() && errors.isEmpty()) {
                return HttpResponse.<String>ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .body(S3Xml.deleteResult(List.of(), List.of()));
            }
            return HttpResponse.ok(S3Xml.deleteResult(deleted, errors))
                    .contentType(MediaType.APPLICATION_XML);

        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final Exception ex) {
            LOG.error("DeleteObjects '{}' failed", bucket, ex);
            return internalError();
        }
    }

    // =========================================================================
    // Phase 3 — bucket configuration PUT handlers
    // =========================================================================

    private HttpResponse<String> putBucketAcl(
            final String bucket, final byte[] body, final String cannedAcl) {
        try {
            final BucketMetadata.AclConfig acl =
                    BucketConfigParser.parseAcl(body, cannedAcl);
            bucketService.updateBucket(bucket, meta -> meta.withAcl(acl));
            return HttpResponse.<String>status(HttpStatus.OK);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return s3Error(S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutBucketAcl '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> putBucketCors(
            final String bucket, final byte[] body) {
        try {
            final BucketMetadata.CorsConfig cors = BucketConfigParser.parseCors(body);
            bucketService.updateBucket(bucket, meta -> meta.withCors(cors));
            return HttpResponse.<String>status(HttpStatus.OK);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return s3Error(S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutBucketCors '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> putBucketTagging(
            final String bucket, final byte[] body) {
        try {
            final var tags = BucketConfigParser.parseTagging(body);
            bucketService.updateBucket(bucket, meta -> meta.withTags(tags.isEmpty() ? null : tags));
            return HttpResponse.<String>status(HttpStatus.OK);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return s3Error(S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutBucketTagging '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> putBucketVersioning(
            final String bucket, final byte[] body) {
        try {
            final BucketMetadata.VersioningConfig vc = BucketConfigParser.parseVersioning(body);
            bucketService.updateBucket(bucket, current -> {
                final BucketMetadata.VersioningConfig existing = current.versioning();
                final BucketMetadata.VersioningStatus newStatus = vc.status() != null
                        ? vc.status()
                        : (existing != null ? existing.status() : null);
                return current.withVersioning(
                        new BucketMetadata.VersioningConfig(newStatus, vc.mfaDelete()));
            });
            return HttpResponse.<String>status(HttpStatus.OK);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return s3Error(S3Xml.error("IllegalVersioningConfigurationException",
                    ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutBucketVersioning '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> putBucketWebsite(
            final String bucket, final byte[] body) {
        try {
            final BucketMetadata.WebsiteConfig ws = BucketConfigParser.parseWebsite(body);
            bucketService.updateBucket(bucket, meta -> meta.withWebsite(ws));
            return HttpResponse.<String>status(HttpStatus.OK);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return s3Error(S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutBucketWebsite '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> putBucketLifecycle(
            final String bucket, final byte[] body) {
        try {
            final BucketMetadata.LifecycleConfig lc = BucketConfigParser.parseLifecycle(body);
            bucketService.updateBucket(bucket, meta -> meta.withLifecycle(lc));
            return HttpResponse.<String>status(HttpStatus.OK);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return s3Error(S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutBucketLifecycle '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> putBucketPolicy(
            final String bucket, final byte[] body) {
        try {
            if (body == null || body.length == 0) {
                return s3Error(S3Xml.error("MalformedPolicy",
                        "Policy body must not be empty.", null), 400);
            }
            final String policyJson = new String(body, StandardCharsets.UTF_8);

            final JsonNode root;
            try {
                root = POLICY_MAPPER.readTree(policyJson);
            } catch (final IOException ex) {
                return s3Error(S3Xml.error("MalformedPolicy",
                        "Invalid JSON in policy document.", null), 400);
            }
            final JsonNode versionNode = root.get("Version");
            if (versionNode != null && !"2012-10-17".equals(versionNode.asText())) {
                return s3Error(S3Xml.error("MalformedPolicy",
                        "Policy document must use Version 2012-10-17.", null), 400);
            }

            bucketService.updateBucket(bucket, meta -> meta.withPolicyJson(policyJson));
            return HttpResponse.<String>status(HttpStatus.NO_CONTENT);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("PutBucketPolicy '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> putPublicAccessBlock(
            final String bucket, final byte[] body) {
        try {
            final BucketMetadata.PublicAccessBlockConfig pab =
                    BucketConfigParser.parsePublicAccessBlock(body);
            bucketService.updateBucket(bucket, meta -> meta.withPublicAccessBlock(pab));
            return HttpResponse.<String>status(HttpStatus.OK);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return s3Error(S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutPublicAccessBlock '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> putBucketRequestPayment(
            final String bucket, final byte[] body) {
        try {
            final String payer = BucketConfigParser.parseRequestPayer(body);
            bucketService.updateBucket(bucket, meta -> meta.withRequestPayer(payer));
            return HttpResponse.<String>status(HttpStatus.OK);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return s3Error(S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutBucketRequestPayment '{}' failed", bucket, ex);
            return internalError();
        }
    }

    // =========================================================================
    // Phase 3 — bucket configuration DELETE handlers
    // =========================================================================

    private HttpResponse<String> deleteBucketCors(final String bucket) {
        return clearBucketConfig(bucket, meta -> meta.withCors(null), "DeleteBucketCors");
    }

    private HttpResponse<String> deleteBucketTagging(final String bucket) {
        return clearBucketConfig(bucket, meta -> meta.withTags(null), "DeleteBucketTagging");
    }

    private HttpResponse<String> deleteBucketWebsite(final String bucket) {
        return clearBucketConfig(bucket, meta -> meta.withWebsite(null), "DeleteBucketWebsite");
    }

    private HttpResponse<String> deleteBucketLifecycle(final String bucket) {
        return clearBucketConfig(bucket, meta -> meta.withLifecycle(null), "DeleteBucketLifecycle");
    }

    private HttpResponse<String> deleteBucketPolicy(final String bucket) {
        return clearBucketConfig(bucket, meta -> meta.withPolicyJson(null), "DeleteBucketPolicy");
    }

    private HttpResponse<String> deletePublicAccessBlock(final String bucket) {
        return clearBucketConfig(bucket,
                meta -> meta.withPublicAccessBlock(null), "DeletePublicAccessBlock");
    }

    private HttpResponse<String> clearBucketConfig(
            final String bucket,
            final java.util.function.UnaryOperator<BucketMetadata> updater,
            final String opName) {
        try {
            bucketService.updateBucket(bucket, updater);
            return HttpResponse.<String>status(HttpStatus.NO_CONTENT);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("{} '{}' failed", opName, bucket, ex);
            return internalError();
        }
    }

    // =========================================================================
    // Phase 3 — bucket configuration GET handlers
    // =========================================================================

    private HttpResponse<String> getBucketAcl(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            final BucketMetadata.AclConfig acl = meta.acl();
            final String owner = meta.owner() != null ? meta.owner() : "";
            final List<S3Xml.GrantEntry> grants;
            if (acl != null && acl.grants() != null && !acl.grants().isEmpty()) {
                grants = acl.grants().stream()
                        .map(g -> new S3Xml.GrantEntry(g.grantee(), g.permission()))
                        .toList();
            } else {
                grants = null;
            }
            final String canned = acl != null ? acl.canned() : "private";
            return HttpResponse.ok(S3Xml.getBucketAcl(owner, canned, grants))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    private HttpResponse<String> getBucketCors(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            if (meta.cors() == null
                    || meta.cors().rules() == null
                    || meta.cors().rules().isEmpty()) {
                return s3Error(S3Xml.error("NoSuchCORSConfiguration",
                        "The CORS configuration does not exist.", null), 404);
            }
            final List<S3Xml.CorsRuleEntry> rules = meta.cors().rules().stream()
                    .map(r -> new S3Xml.CorsRuleEntry(
                            r.allowedOrigins(), r.allowedMethods(),
                            r.allowedHeaders(), r.exposeHeaders(), r.maxAgeSeconds()))
                    .toList();
            return HttpResponse.ok(S3Xml.getBucketCors(rules))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    private HttpResponse<String> getBucketTagging(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            return HttpResponse.ok(S3Xml.getBucketTagging(meta.tags()))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    private HttpResponse<String> getBucketVersioning(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            final BucketMetadata.VersioningConfig vc = meta.versioning();
            final String status = vc == null || vc.status() == null ? null
                    : switch (vc.status()) {
                        case ENABLED   -> "Enabled";
                        case SUSPENDED -> "Suspended";
                        case DISABLED  -> null;
                    };
            final boolean mfa = vc != null && vc.mfaDelete();
            return HttpResponse.ok(S3Xml.getBucketVersioning(status, mfa))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    private HttpResponse<String> getBucketWebsite(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            if (meta.website() == null || !meta.website().enabled()) {
                return s3Error(S3Xml.error("NoSuchWebsiteConfiguration",
                        "The specified bucket does not have a website configuration.", null), 404);
            }
            return HttpResponse.ok(S3Xml.getBucketWebsite(
                    meta.website().indexDocument(), meta.website().errorDocument()))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    private HttpResponse<String> getBucketLifecycle(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            if (meta.lifecycle() == null
                    || meta.lifecycle().rules() == null
                    || meta.lifecycle().rules().isEmpty()) {
                return s3Error(S3Xml.error("NoSuchLifecycleConfiguration",
                        "The lifecycle configuration does not exist.", null), 404);
            }
            final List<S3Xml.LifecycleRuleEntry> rules = meta.lifecycle().rules().stream()
                    .map(r -> new S3Xml.LifecycleRuleEntry(
                            r.id(), r.status(), r.prefix(), r.expirationDays()))
                    .toList();
            return HttpResponse.ok(S3Xml.getBucketLifecycle(rules))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    private HttpResponse<String> getBucketPolicy(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            if (meta.policyJson() == null || meta.policyJson().isEmpty()) {
                return s3Error(S3Xml.error("NoSuchBucketPolicy",
                        "The bucket policy does not exist.", null), 404);
            }
            return HttpResponse.ok(meta.policyJson())
                    .contentType(MediaType.APPLICATION_JSON_TYPE);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    private HttpResponse<String> getBucketPolicyStatus(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            // A bucket is "public" if it has a public-read canned ACL
            final boolean isPublic = meta.acl() != null
                    && ("public-read".equals(meta.acl().canned())
                            || "public-read-write".equals(meta.acl().canned()));
            return HttpResponse.ok(S3Xml.getBucketPolicyStatus(isPublic))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    private HttpResponse<String> getBucketPublicAccessBlock(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            if (meta.publicAccessBlock() == null) {
                return s3Error(S3Xml.error("NoSuchPublicAccessBlockConfiguration",
                        "The public access block configuration was not found.", null), 404);
            }
            final BucketMetadata.PublicAccessBlockConfig pab = meta.publicAccessBlock();
            return HttpResponse.ok(S3Xml.getPublicAccessBlock(
                    pab.blockPublicAcls(), pab.ignorePublicAcls(),
                    pab.blockPublicPolicy(), pab.restrictPublicBuckets()))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    private HttpResponse<String> getBucketRequestPayment(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            final String payer = meta.requestPayer() != null ? meta.requestPayer() : "BucketOwner";
            return HttpResponse.ok(S3Xml.getBucketRequestPayment(payer))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    // =========================================================================
    // ListMultipartUploads / GetBucketLocation (moved from @Get)
    // =========================================================================

    private HttpResponse<String> listMultipartUploads(
            final String bucket,
            final String prefix,
            final String delimiter,
            final String keyMarker,
            final String uploadIdMarker,
            final int maxUploads) {
        try {
            final MultipartService.ListUploadsPage page = multipartService.listMultipartUploads(
                    bucket,
                    prefix.isEmpty() ? null : prefix,
                    delimiter.isEmpty() ? null : delimiter,
                    keyMarker.isEmpty() ? null : keyMarker,
                    uploadIdMarker.isEmpty() ? null : uploadIdMarker,
                    maxUploads);

            final var uploads = page.uploads().stream()
                    .map(u -> new S3Xml.UploadEntry(
                            u.key(), u.uploadId(), u.owner(), "STANDARD", u.initiated()))
                    .toList();

            final String xml = S3Xml.listMultipartUploads(
                    bucket,
                    prefix.isEmpty() ? null : prefix,
                    delimiter.isEmpty() ? null : delimiter,
                    keyMarker.isEmpty() ? null : keyMarker,
                    uploadIdMarker.isEmpty() ? null : uploadIdMarker,
                    maxUploads,
                    page.truncated(),
                    page.nextKeyMarker(),
                    page.nextUploadIdMarker(),
                    uploads,
                    page.commonPrefixes());
            return HttpResponse.ok(xml).contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("ListMultipartUploads '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> getBucketLocation(final String bucket) {
        try {
            final String region = bucketService.getBucketLocation(bucket);
            return HttpResponse.ok(S3Xml.locationConstraint(region))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    // =========================================================================
    // Phase 4 — object versioning + object-lock (bucket-level)
    // =========================================================================

    private HttpResponse<String> listObjectVersions(
            final String bucket,
            final String prefix,
            final String delimiter,
            final int maxKeys,
            final String keyMarker,
            final String versionIdMarker) {
        try {
            bucketService.headBucket(bucket);
            final String owner = bucketService.getBucketMetadata(bucket).owner();
            final ObjectMetadataStore.ListVersionsPage page = objectService.listObjectVersions(
                    bucket,
                    prefix.isEmpty()          ? null : prefix,
                    delimiter.isEmpty()       ? null : delimiter,
                    maxKeys,
                    keyMarker.isEmpty()       ? null : keyMarker,
                    versionIdMarker.isEmpty() ? null : versionIdMarker);

            final List<S3Xml.VersionListEntry> entries = page.versions().stream()
                    .map(v -> new S3Xml.VersionListEntry(
                            v.key(), v.versionId(), v.etag(), v.size(),
                            v.deleteMarker(), v.isLatest(), v.lastModified(), v.storageClass()))
                    .toList();

            final String xml = S3Xml.listObjectVersions(
                    bucket,
                    prefix.isEmpty()          ? null : prefix,
                    delimiter.isEmpty()       ? null : delimiter,
                    maxKeys,
                    keyMarker.isEmpty()       ? null : keyMarker,
                    versionIdMarker.isEmpty() ? null : versionIdMarker,
                    page.truncated(),
                    page.nextKeyMarker(),
                    page.nextVersionIdMarker(),
                    owner != null ? owner : "",
                    entries,
                    page.commonPrefixes());
            return HttpResponse.ok(xml).contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IOException ex) {
            LOG.error("ListObjectVersions '{}' failed", bucket, ex);
            return internalError();
        }
    }

    private HttpResponse<String> getObjectLockConfiguration(final String bucket) {
        try {
            final BucketMetadata meta = bucketService.getBucketMetadata(bucket);
            final BucketMetadata.ObjectLockConfig olc = meta.objectLock();
            if (olc == null) {
                return s3Error(S3Xml.error("ObjectLockConfigurationNotFoundError",
                        "Object Lock configuration does not exist for this bucket.", null), 404);
            }
            return HttpResponse.ok(S3Xml.getObjectLockConfiguration(
                    olc.enabled(), olc.defaultRetentionMode(),
                    olc.defaultRetentionDays(), olc.defaultRetentionYears()))
                    .contentType(MediaType.APPLICATION_XML);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        }
    }

    private HttpResponse<String> putObjectLockConfiguration(
            final String bucket, final byte[] body) {
        try {
            final BucketMetadata.ObjectLockConfig olc =
                    ObjectConfigParser.parseObjectLockConfiguration(body);
            bucketService.updateBucket(bucket, meta -> meta.withObjectLock(olc));
            return HttpResponse.<String>status(HttpStatus.OK);
        } catch (final S3Exception ex) {
            return s3Error(ex);
        } catch (final IllegalArgumentException ex) {
            return s3Error(S3Xml.error("MalformedXML", ex.getMessage(), null), 400);
        } catch (final IOException ex) {
            LOG.error("PutObjectLockConfiguration '{}' failed", bucket, ex);
            return internalError();
        }
    }

    // =========================================================================
    // Error helpers
    // =========================================================================

    private static String parseLocationConstraint(final byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            final var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final var doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(body));
            final var nodes = doc.getElementsByTagName("LocationConstraint");
            if (nodes.getLength() > 0) {
                final String val = nodes.item(0).getTextContent().trim();
                return val.isEmpty() ? null : val;
            }
        } catch (final Exception ex) {
            // Malformed body — treat as no location constraint
        }
        return null;
    }

    static <T> HttpResponse<T> s3Error(final S3Exception ex) {
        return s3Error(S3Xml.error(ex.getCode(), ex.getMessage(), null), ex.getHttpStatus());
    }

    @SuppressWarnings("unchecked")
    static <T> HttpResponse<T> s3Error(final String xml, final int status) {
        return (HttpResponse<T>) HttpResponse.status(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }

    static <T> HttpResponse<T> internalError() {
        return s3Error(S3Xml.error("InternalError",
                "We encountered an internal error. Please try again.", null), 500);
    }
}
