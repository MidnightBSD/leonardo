/*
 * Copyright 2026 The Leonardo Authors.
 * Licensed under the Apache License, Version 2.0.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.midnightbsd.leonardo.xml;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class S3XmlTest {

    private static final Instant WHEN = Instant.parse("2026-10-31T12:00:00Z");

    @Test
    void listObjectVersionsEmitsTheLiteralNullVersionIdForUnversionedObjects() {
        // boto3 raises KeyError and mc skips the entry when <VersionId> is absent,
        // which made unversioned buckets impossible to empty with `mc rm --versions`.
        final String xml = S3Xml.listObjectVersions("b", null, null, 1000, null, null,
                false, null, null, "owner",
                List.of(new S3Xml.VersionListEntry("k", null, "\"e\"", 3, false, true, WHEN, null),
                        new S3Xml.VersionListEntry("k", null, null, 0, true, false, WHEN, null)),
                List.of());

        assertThat(xml).contains("<Version><Key>k</Key><VersionId>null</VersionId>");
        assertThat(xml).contains("<DeleteMarker><Key>k</Key><VersionId>null</VersionId>");
    }

    @Test
    void listObjectVersionsEmitsNextMarkersOnlyWhenTruncated() {
        final String truncated = S3Xml.listObjectVersions("b", null, null, 1, null, null,
                true, "k", null, "owner", List.of(), List.of());
        assertThat(truncated)
                .contains("<NextKeyMarker>k</NextKeyMarker>")
                .contains("<NextVersionIdMarker>null</NextVersionIdMarker>");

        final String complete = S3Xml.listObjectVersions("b", null, null, 1, null, null,
                false, "k", "v", "owner", List.of(), List.of());
        assertThat(complete).doesNotContain("NextKeyMarker").doesNotContain("NextVersionIdMarker");
    }

    @Test
    void listObjectsIncludesOwnerOnlyWhenOneIsGiven() {
        final String withOwner = S3Xml.listObjectsV2("b", null, null, 1000, false, null, null, 1,
                List.of(new S3Xml.ObjectEntry("k", WHEN, "\"e\"", 3, "STANDARD", "alice")), List.of());
        assertThat(withOwner).contains("<Owner><ID>alice</ID><DisplayName>alice</DisplayName></Owner>");

        final String withoutOwner = S3Xml.listObjectsV2("b", null, null, 1000, false, null, null, 1,
                List.of(new S3Xml.ObjectEntry("k", WHEN, "\"e\"", 3, "STANDARD")), List.of());
        assertThat(withoutOwner).doesNotContain("<Owner>");
    }

    @Test
    void copyPartResultCarriesEtagAndLastModified() {
        final String xml = S3Xml.copyPartResult("\"abc\"", WHEN);
        assertThat(xml)
                .contains("<CopyPartResult")
                .contains("<ETag>&quot;abc&quot;</ETag>")
                .contains("<LastModified>2026-10-31T12:00:00.000Z</LastModified>");
    }

    @Test
    void deleteObjectsRequestCarriesVersionIds() throws Exception {
        final DeleteObjectsRequest req = DeleteObjectsRequest.parse((
                "<Delete><Quiet>true</Quiet>"
                + "<Object><Key>a</Key></Object>"
                + "<Object><Key>b</Key><VersionId>01HV</VersionId></Object>"
                + "<Object><Key>c</Key><VersionId>null</VersionId></Object>"
                + "</Delete>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(req.quiet()).isTrue();
        assertThat(req.objects()).containsExactly(
                new DeleteObjectsRequest.ObjectRef("a", null),
                new DeleteObjectsRequest.ObjectRef("b", "01HV"),
                new DeleteObjectsRequest.ObjectRef("c", "null"));
        assertThat(req.keys()).containsExactly("a", "b", "c");
    }

    @Test
    void deleteResultReportsVersionsAndMarkers() {
        final String xml = S3Xml.deleteResult(
                List.of(new S3Xml.DeletedEntry("plain"),
                        new S3Xml.DeletedEntry("marked", null, true, "01HM"),
                        new S3Xml.DeletedEntry("versioned", "01HV", false, null),
                        new S3Xml.DeletedEntry("unmarked", "01HM", true, null)),
                List.of(new S3Xml.DeleteErrorEntry("bad", "01HX", "NoSuchVersion", "gone")));
        assertThat(xml)
                .contains("<Deleted><Key>plain</Key></Deleted>")
                .contains("<Deleted><Key>marked</Key><DeleteMarker>true</DeleteMarker>"
                        + "<DeleteMarkerVersionId>01HM</DeleteMarkerVersionId></Deleted>")
                .contains("<Deleted><Key>versioned</Key><VersionId>01HV</VersionId></Deleted>")
                .contains("<Deleted><Key>unmarked</Key><VersionId>01HM</VersionId><DeleteMarker>true</DeleteMarker></Deleted>")
                .contains("<Error><Key>bad</Key><VersionId>01HX</VersionId><Code>NoSuchVersion</Code>");
    }
}
