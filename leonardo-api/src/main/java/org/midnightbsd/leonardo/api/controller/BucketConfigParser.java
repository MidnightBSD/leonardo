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

import org.midnightbsd.leonardo.core.bucket.BucketMetadata;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XXE-safe XML parsers for S3 bucket configuration request bodies (M4 Phase 3).
 * All methods return {@code BucketMetadata.*} types; they are placed here (not in
 * {@code leonardo-xml}) because that module has no dependency on {@code leonardo-core}.
 */
final class BucketConfigParser {

    private BucketConfigParser() {}

    // -------------------------------------------------------------------------
    // ACL
    // -------------------------------------------------------------------------

    /**
     * Parses PutBucketAcl. If {@code cannedAclHeader} is non-empty it takes
     * precedence over the XML body.
     */
    static BucketMetadata.AclConfig parseAcl(
            final byte[] body, final String cannedAclHeader) {
        if (cannedAclHeader != null && !cannedAclHeader.isEmpty()) {
            return new BucketMetadata.AclConfig(cannedAclHeader, null);
        }
        if (body == null || body.length == 0) {
            return new BucketMetadata.AclConfig("private", null);
        }
        try {
            final Document doc = parseXml(body);
            final var grants = new ArrayList<BucketMetadata.AclGrant>();
            final NodeList grantNodes = doc.getElementsByTagName("Grant");
            for (int i = 0; i < grantNodes.getLength(); i++) {
                final Element grant = (Element) grantNodes.item(i);
                final Element granteeEl = firstElement(grant, "Grantee");
                String grantee = "";
                if (granteeEl != null) {
                    final String id    = text(granteeEl, "ID");
                    final String email = text(granteeEl, "EmailAddress");
                    final String uri   = text(granteeEl, "URI");
                    grantee = id != null ? id : (email != null ? email : (uri != null ? uri : ""));
                }
                final String permission = text(grant, "Permission");
                grants.add(new BucketMetadata.AclGrant(grantee,
                        permission != null ? permission : ""));
            }
            return new BucketMetadata.AclConfig(null, grants.isEmpty() ? null : grants);
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed ACL body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // CORS
    // -------------------------------------------------------------------------

    static BucketMetadata.CorsConfig parseCors(final byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("PutBucketCors body must not be empty.");
        }
        try {
            final Document doc  = parseXml(body);
            final NodeList nodes = doc.getElementsByTagName("CORSRule");
            final var rules = new ArrayList<BucketMetadata.CorsRule>(nodes.getLength());
            for (int i = 0; i < nodes.getLength(); i++) {
                final Element el = (Element) nodes.item(i);
                rules.add(new BucketMetadata.CorsRule(
                        textList(el, "AllowedOrigin"),
                        textList(el, "AllowedMethod"),
                        textList(el, "AllowedHeader"),
                        textList(el, "ExposeHeader"),
                        intOrNull(el, "MaxAgeSeconds")));
            }
            return new BucketMetadata.CorsConfig(rules);
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed CORS body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Tagging
    // -------------------------------------------------------------------------

    static Map<String, String> parseTagging(final byte[] body) {
        if (body == null || body.length == 0) return Map.of();
        try {
            final Document doc  = parseXml(body);
            final NodeList tags = doc.getElementsByTagName("Tag");
            final var result = new LinkedHashMap<String, String>(tags.getLength());
            for (int i = 0; i < tags.getLength(); i++) {
                final Element el  = (Element) tags.item(i);
                final String key  = text(el, "Key");
                final String val  = text(el, "Value");
                if (key != null) result.put(key, val != null ? val : "");
            }
            return result;
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed Tagging body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Versioning
    // -------------------------------------------------------------------------

    static BucketMetadata.VersioningConfig parseVersioning(final byte[] body) {
        if (body == null || body.length == 0) {
            return new BucketMetadata.VersioningConfig(null, false);
        }
        try {
            final Document doc = parseXml(body);
            final String statusStr = text(doc.getDocumentElement(), "Status");
            final String mfaStr    = text(doc.getDocumentElement(), "MfaDelete");
            final BucketMetadata.VersioningStatus status = statusStr == null || statusStr.isEmpty()
                    ? null
                    : switch (statusStr) {
                        case "Enabled"   -> BucketMetadata.VersioningStatus.ENABLED;
                        case "Suspended" -> BucketMetadata.VersioningStatus.SUSPENDED;
                        default -> throw new IllegalArgumentException(
                                "Unknown versioning status: " + statusStr);
                    };
            final boolean mfaDelete = "Enabled".equals(mfaStr);
            return new BucketMetadata.VersioningConfig(status, mfaDelete);
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed Versioning body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Website
    // -------------------------------------------------------------------------

    static BucketMetadata.WebsiteConfig parseWebsite(final byte[] body) {
        if (body == null || body.length == 0) {
            return new BucketMetadata.WebsiteConfig(true, null, null);
        }
        try {
            final Document doc    = parseXml(body);
            final Element root    = doc.getDocumentElement();
            final Element indexEl = firstElement(root, "IndexDocument");
            final Element errorEl = firstElement(root, "ErrorDocument");
            final String indexDoc = indexEl != null ? text(indexEl, "Suffix") : null;
            final String errorDoc = errorEl != null ? text(errorEl, "Key")    : null;
            return new BucketMetadata.WebsiteConfig(true, indexDoc, errorDoc);
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed Website body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    static BucketMetadata.LifecycleConfig parseLifecycle(final byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("PutBucketLifecycleConfiguration body must not be empty.");
        }
        try {
            final Document doc   = parseXml(body);
            final NodeList nodes = doc.getElementsByTagName("Rule");
            final var rules = new ArrayList<BucketMetadata.LifecycleRule>(nodes.getLength());
            for (int i = 0; i < nodes.getLength(); i++) {
                final Element el     = (Element) nodes.item(i);
                final String id      = text(el, "ID");
                final String status  = text(el, "Status");
                // Support both legacy <Prefix> and new <Filter><Prefix> shapes
                String prefix = text(el, "Prefix");
                if (prefix == null) {
                    final Element filter = firstElement(el, "Filter");
                    if (filter != null) prefix = text(filter, "Prefix");
                }
                // Expiration.Days
                final Element expEl   = firstElement(el, "Expiration");
                final Integer expDays = expEl != null ? intOrNull(expEl, "Days") : null;
                rules.add(new BucketMetadata.LifecycleRule(id, status, prefix, expDays));
            }
            return new BucketMetadata.LifecycleConfig(rules);
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed Lifecycle body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // PublicAccessBlock
    // -------------------------------------------------------------------------

    static BucketMetadata.PublicAccessBlockConfig parsePublicAccessBlock(final byte[] body) {
        if (body == null || body.length == 0) {
            return new BucketMetadata.PublicAccessBlockConfig(false, false, false, false);
        }
        try {
            final Document doc = parseXml(body);
            final Element root = doc.getDocumentElement();
            return new BucketMetadata.PublicAccessBlockConfig(
                    boolOrFalse(root, "BlockPublicAcls"),
                    boolOrFalse(root, "IgnorePublicAcls"),
                    boolOrFalse(root, "BlockPublicPolicy"),
                    boolOrFalse(root, "RestrictPublicBuckets"));
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException(
                    "Malformed PublicAccessBlock body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // RequestPayment
    // -------------------------------------------------------------------------

    static String parseRequestPayer(final byte[] body) {
        if (body == null || body.length == 0) return "BucketOwner";
        try {
            final Document doc = parseXml(body);
            final String payer = text(doc.getDocumentElement(), "Payer");
            if (!"Requester".equals(payer) && !"BucketOwner".equals(payer)) {
                throw new IllegalArgumentException("Payer must be 'Requester' or 'BucketOwner'");
            }
            return payer;
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException(
                    "Malformed RequestPayment body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Document parseXml(final byte[] body) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        final DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(body));
    }

    private static String text(final Element parent, final String tagName) {
        final NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        final String val = nodes.item(0).getTextContent();
        return val != null ? val.trim() : null;
    }

    private static Element firstElement(final Element parent, final String tagName) {
        final NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    private static List<String> textList(final Element parent, final String tagName) {
        final NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        final var result = new ArrayList<String>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add(nodes.item(i).getTextContent().trim());
        }
        return result;
    }

    private static Integer intOrNull(final Element parent, final String tagName) {
        final String val = text(parent, tagName);
        if (val == null || val.isEmpty()) return null;
        try { return Integer.parseInt(val); } catch (final NumberFormatException ex) { return null; }
    }

    private static boolean boolOrFalse(final Element parent, final String tagName) {
        return "true".equalsIgnoreCase(text(parent, tagName));
    }

    // -------------------------------------------------------------------------
    // Phase 6 — notifications, logging, replication
    // -------------------------------------------------------------------------

    /**
     * Validates that the body is well-formed XML and returns it as a UTF-8 string.
     * Used for notification and replication configs that are stored verbatim.
     */
    public static String parseRawXml(final byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.newDocumentBuilder().parse(new ByteArrayInputStream(body)); // validate only
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed XML: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 7 — encryption, ownership controls, accelerate
    // -------------------------------------------------------------------------

    /** Parses {@code PutBucketEncryption} body; validates XML and returns raw UTF-8 string. */
    public static String parseEncryptionConfig(final byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("PutBucketEncryption body must not be empty.");
        }
        return parseRawXml(body);
    }

    /** Parses {@code PutBucketOwnershipControls} body; validates XML and returns raw UTF-8 string. */
    public static String parseOwnershipControls(final byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("PutBucketOwnershipControls body must not be empty.");
        }
        return parseRawXml(body);
    }

    /**
     * Parses {@code PutBucketAccelerateConfiguration} body.
     * Returns {@code "Enabled"}, {@code "Suspended"}, or {@code null} for an empty config.
     */
    public static String parseAccelerateStatus(final byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            final String status = text(doc.getDocumentElement(), "Status");
            if (status == null || status.isEmpty()) return null;
            if (!"Enabled".equals(status) && !"Suspended".equals(status)) {
                throw new IllegalArgumentException("AccelerateConfiguration Status must be 'Enabled' or 'Suspended'");
            }
            return status;
        } catch (final IllegalArgumentException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed AccelerateConfiguration XML: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 8 — analytics, metrics, inventory, intelligent-tiering, ABAC
    // -------------------------------------------------------------------------

    /** Parses {@code PutBucketAnalyticsConfiguration} body; validates XML and returns raw UTF-8 string. */
    public static String parseAnalyticsConfiguration(final byte[] body) {
        if (body == null || body.length == 0)
            throw new IllegalArgumentException("AnalyticsConfiguration body must not be empty.");
        return parseRawXml(body);
    }

    /** Parses {@code PutBucketMetricsConfiguration} body; validates XML and returns raw UTF-8 string. */
    public static String parseMetricsConfiguration(final byte[] body) {
        if (body == null || body.length == 0)
            throw new IllegalArgumentException("MetricsConfiguration body must not be empty.");
        return parseRawXml(body);
    }

    /** Parses {@code PutBucketInventoryConfiguration} body; validates XML and returns raw UTF-8 string. */
    public static String parseInventoryConfiguration(final byte[] body) {
        if (body == null || body.length == 0)
            throw new IllegalArgumentException("InventoryConfiguration body must not be empty.");
        return parseRawXml(body);
    }

    /** Parses {@code PutBucketIntelligentTieringConfiguration} body; validates XML and returns raw UTF-8 string. */
    public static String parseIntelligentTieringConfiguration(final byte[] body) {
        if (body == null || body.length == 0)
            throw new IllegalArgumentException("IntelligentTieringConfiguration body must not be empty.");
        return parseRawXml(body);
    }

    /** Parses {@code PutBucketAbac} body; validates XML and returns raw UTF-8 string. */
    public static String parseAbacConfiguration(final byte[] body) {
        if (body == null || body.length == 0)
            throw new IllegalArgumentException("AbacConfiguration body must not be empty.");
        return parseRawXml(body);
    }

    /** Parses a {@code PutBucketLogging} request body, returning a {@link BucketMetadata.LoggingConfig}. */
    public static BucketMetadata.LoggingConfig parseLoggingConfig(final byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
            f.setNamespaceAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            final Element root = doc.getDocumentElement();
            final Element enabled = firstElement(root, "LoggingEnabled");
            if (enabled == null) {
                return null; // empty BucketLoggingStatus = disable logging
            }
            final String targetBucket = text(enabled, "TargetBucket");
            final String targetPrefix = text(enabled, "TargetPrefix");
            if (targetBucket == null || targetBucket.isEmpty()) {
                throw new IllegalArgumentException("LoggingEnabled requires TargetBucket");
            }
            return new BucketMetadata.LoggingConfig(targetBucket,
                    targetPrefix != null ? targetPrefix : "");
        } catch (final IllegalArgumentException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed BucketLoggingStatus XML: " + ex.getMessage(), ex);
        }
    }
}
