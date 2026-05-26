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

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed body of a {@code CompleteMultipartUpload} request.
 *
 * <p>Expected XML shape:
 * <pre>{@code
 * <CompleteMultipartUpload>
 *   <Part>
 *     <PartNumber>1</PartNumber>
 *     <ETag>"etag1"</ETag>
 *   </Part>
 *   ...
 * </CompleteMultipartUpload>
 * }</pre>
 */
public record CompleteMultipartUploadRequest(List<Part> parts) {

    /** A single (partNumber, etag) pair from the client's completion list. */
    public record Part(int partNumber, String etag) {}

    /**
     * Parses the XML request body.
     *
     * @throws IllegalArgumentException on malformed XML or missing required elements
     */
    public static CompleteMultipartUploadRequest parse(final byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("CompleteMultipartUpload body must not be empty.");
        }
        try {
            final var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            final var doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(body));

            final NodeList partNodes = doc.getElementsByTagName("Part");
            final var parts = new ArrayList<Part>(partNodes.getLength());
            for (int i = 0; i < partNodes.getLength(); i++) {
                final Element el = (Element) partNodes.item(i);
                final int partNumber = Integer.parseInt(
                        el.getElementsByTagName("PartNumber").item(0).getTextContent().trim());
                final String etag = el.getElementsByTagName("ETag").item(0)
                        .getTextContent().trim()
                        .replace("\"", "");
                parts.add(new Part(partNumber, etag));
            }
            return new CompleteMultipartUploadRequest(parts);
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalArgumentException(
                    "Failed to parse CompleteMultipartUpload body: " + ex.getMessage(), ex);
        }
    }
}
