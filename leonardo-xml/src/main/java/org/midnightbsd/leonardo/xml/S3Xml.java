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
