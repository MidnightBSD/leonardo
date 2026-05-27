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
import org.midnightbsd.leonardo.core.object.ObjectMetadata;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XXE-safe XML parsers for S3 object configuration request bodies (M5 Phase 4+5).
 */
final class ObjectConfigParser {

    private ObjectConfigParser() {}

    // -------------------------------------------------------------------------
    // Object ACL
    // -------------------------------------------------------------------------

    /**
     * Returns the canned ACL. If the header is present it takes precedence;
     * if the body contains a full AccessControlPolicy the owner FULL_CONTROL
     * grant is used; otherwise defaults to "private".
     */
    static String parseObjectAcl(final byte[] body, final String cannedAclHeader) {
        if (cannedAclHeader != null && !cannedAclHeader.isEmpty()) {
            return cannedAclHeader;
        }
        if (body == null || body.length == 0) {
            return "private";
        }
        try {
            final Document doc = parseXml(body);
            // If body present but no recognizable canned value, default private
            final Element root = doc.getDocumentElement();
            final String owner = text(root, "ID");
            return owner != null ? "private" : "private";
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed ACL body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Object Tagging
    // -------------------------------------------------------------------------

    static Map<String, String> parseObjectTagging(final byte[] body) {
        if (body == null || body.length == 0) return Map.of();
        try {
            final Document doc = parseXml(body);
            final var tags = doc.getElementsByTagName("Tag");
            final var result = new LinkedHashMap<String, String>(tags.getLength());
            for (int i = 0; i < tags.getLength(); i++) {
                final Element el = (Element) tags.item(i);
                final String key = text(el, "Key");
                final String val = text(el, "Value");
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
    // Legal Hold
    // -------------------------------------------------------------------------

    static boolean parseObjectLegalHold(final byte[] body) {
        if (body == null || body.length == 0) return false;
        try {
            final Document doc = parseXml(body);
            final String status = text(doc.getDocumentElement(), "Status");
            return "ON".equalsIgnoreCase(status);
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed LegalHold body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Retention
    // -------------------------------------------------------------------------

    static ObjectMetadata.RetentionConfig parseObjectRetention(final byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("PutObjectRetention body must not be empty.");
        }
        try {
            final Document doc = parseXml(body);
            final Element root = doc.getDocumentElement();
            final String mode = text(root, "Mode");
            final String dateStr = text(root, "RetainUntilDate");
            final Instant retainUntilDate = dateStr != null && !dateStr.isEmpty()
                    ? Instant.parse(dateStr) : null;
            if (mode == null || mode.isEmpty()) {
                throw new IllegalArgumentException("Retention Mode is required.");
            }
            if (!"GOVERNANCE".equals(mode) && !"COMPLIANCE".equals(mode)) {
                throw new IllegalArgumentException("Retention Mode must be GOVERNANCE or COMPLIANCE.");
            }
            return new ObjectMetadata.RetentionConfig(mode, retainUntilDate);
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Malformed Retention body: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // ObjectLockConfiguration (bucket-level)
    // -------------------------------------------------------------------------

    static BucketMetadata.ObjectLockConfig parseObjectLockConfiguration(final byte[] body) {
        if (body == null || body.length == 0) {
            return new BucketMetadata.ObjectLockConfig(false, null, null, null);
        }
        try {
            final Document doc = parseXml(body);
            final Element root = doc.getDocumentElement();
            final String enabledStr = text(root, "ObjectLockEnabled");
            final boolean enabled = "Enabled".equalsIgnoreCase(enabledStr);

            // Optional default retention rule
            final var ruleNodes = root.getElementsByTagName("DefaultRetention");
            String mode = null;
            Integer days = null;
            Integer years = null;
            if (ruleNodes.getLength() > 0) {
                final Element dr = (Element) ruleNodes.item(0);
                mode = text(dr, "Mode");
                final String daysStr = text(dr, "Days");
                final String yearsStr = text(dr, "Years");
                days  = (daysStr  != null && !daysStr.isEmpty())  ? Integer.parseInt(daysStr)  : null;
                years = (yearsStr != null && !yearsStr.isEmpty()) ? Integer.parseInt(yearsStr) : null;
            }
            return new BucketMetadata.ObjectLockConfig(enabled, mode, days, years);
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException(
                    "Malformed ObjectLockConfiguration body: " + ex.getMessage(), ex);
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
        final var nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        final String val = nodes.item(0).getTextContent();
        return val != null ? val.trim() : null;
    }
}
