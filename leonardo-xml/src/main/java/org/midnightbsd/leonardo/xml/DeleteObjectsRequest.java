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
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the XML body of an S3 {@code DeleteObjects} ({@code POST /<bucket>?delete}) request.
 *
 * <pre>{@code
 * <Delete>
 *   <Quiet>false</Quiet>
 *   <Object><Key>key1</Key></Object>
 *   <Object><Key>key2</Key><VersionId>01H...</VersionId></Object>
 * </Delete>
 * }</pre>
 *
 * <p>DocType declarations are disallowed to prevent XXE attacks.
 */
public record DeleteObjectsRequest(boolean quiet, List<ObjectRef> objects) {

    /** One {@code <Object>} element; {@code versionId} is null when absent. */
    public record ObjectRef(String key, String versionId) {}

    /** The keys in request order, for callers that do not care about versions. */
    public List<String> keys() {
        return objects.stream().map(ObjectRef::key).toList();
    }

    /**
     * Parses the request body.
     *
     * @throws IllegalArgumentException if the XML is malformed or empty
     */
    public static DeleteObjectsRequest parse(final byte[] body)
            throws ParserConfigurationException, IOException, SAXException {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("DeleteObjects request body is empty");
        }
        final var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(false);
        // Prevent XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        final var doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(body));
        final var root = doc.getDocumentElement();

        boolean quiet = false;
        final NodeList quietNodes = root.getElementsByTagName("Quiet");
        if (quietNodes.getLength() > 0) {
            quiet = "true".equalsIgnoreCase(quietNodes.item(0).getTextContent().trim());
        }

        final var objects = new ArrayList<ObjectRef>();
        final NodeList objectNodes = root.getElementsByTagName("Object");
        for (int i = 0; i < objectNodes.getLength(); i++) {
            final var obj = (Element) objectNodes.item(i);
            final NodeList keyNodes = obj.getElementsByTagName("Key");
            if (keyNodes.getLength() > 0) {
                final String key = keyNodes.item(0).getTextContent();
                if (key != null && !key.isEmpty()) {
                    final NodeList versionNodes = obj.getElementsByTagName("VersionId");
                    String versionId = null;
                    if (versionNodes.getLength() > 0) {
                        final String v = versionNodes.item(0).getTextContent();
                        versionId = v == null || v.isBlank() ? null : v.trim();
                    }
                    objects.add(new ObjectRef(key, versionId));
                }
            }
        }

        return new DeleteObjectsRequest(quiet, List.copyOf(objects));
    }
}
