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
package org.midnightbsd.leonardo.conformance;

import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Base class for all conformance tests.
 *
 * <p>The static initialiser creates the directory layout and api-keys.yaml that
 * {@code PosixHygieneCheck} and {@code YamlApiKeyStore} require before the
 * Micronaut context starts. (Static initialisers run when the class is loaded by
 * the JVM, which happens before JUnit 5's extension callbacks.)
 *
 * <p>Each test class gets its own Micronaut context bound to a random OS port
 * (port: 0 in application-conformance.yml), which avoids TCP TIME_WAIT conflicts
 * on Linux when multiple test classes start and stop contexts in the same JVM run.
 * The actual port is obtained from the injected {@link EmbeddedServer}.
 *
 * <p>Each test method gets a unique bucket (created and torn down via {@link #setupClientAndBucket}
 * and {@link #cleanupBucket}).
 */
@MicronautTest(environments = "conformance")
abstract class ConformanceBase {

    static final String ACCESS_KEY  = "AKIACONFORMANCE00001";
    static final String SECRET_KEY  = "ConformanceTestSecretKey123456789012345";
    static final String REGION_NAME = "us-east-1";
    static final Path   BASE_DIR    = Path.of("/tmp/leonardo-conformance");

    // One-time setup: runs when the class is first loaded, before any JUnit 5
    // extension callbacks (including Micronaut's BeforeAllCallback).
    static {
        try {
            setupTestEnvironment();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Injected after Micronaut context starts; provides the actual bound port. */
    @Inject
    EmbeddedServer server;

    /** S3 client pre-configured for the loopback server; rebuilt per test. */
    S3Client s3;

    /** Unique bucket name for the current test method. */
    String bucket;

    @BeforeEach
    final void setupClientAndBucket() {
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getPort()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .region(Region.of(REGION_NAME))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        bucket = "cf-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        s3.createBucket(r -> r.bucket(bucket));
    }

    @AfterEach
    final void cleanupBucket() {
        if (s3 != null) {
            deleteBucketFully(bucket);
            s3.close();
        }
    }

    /**
     * Empties and deletes a bucket, tolerating buckets that do not exist and
     * buckets that have versioning enabled.
     */
    final void deleteBucketFully(final String b) {
        try {
            deleteAllVersions(b);
            deleteAllObjects(b);
            s3.deleteBucket(r -> r.bucket(b));
        } catch (S3Exception ignored) {
            // bucket may not exist if the test failed before creating it
        }
    }

    private void deleteAllObjects(final String b) {
        String token = null;
        do {
            final String ct = token;
            final ListObjectsV2Response page = s3.listObjectsV2(r -> {
                r.bucket(b);
                if (ct != null) {
                    r.continuationToken(ct);
                }
            });
            if (!page.contents().isEmpty()) {
                final List<ObjectIdentifier> ids = page.contents().stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .toList();
                s3.deleteObjects(r -> r.bucket(b).delete(
                        Delete.builder().objects(ids).quiet(true).build()));
            }
            token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (token != null);
    }

    private void deleteAllVersions(final String b) {
        try {
            final ListObjectVersionsResponse vr = s3.listObjectVersions(r -> r.bucket(b));
            final List<ObjectIdentifier> ids = new ArrayList<>();
            for (final ObjectVersion v : vr.versions()) {
                ids.add(ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build());
            }
            for (final DeleteMarkerEntry dm : vr.deleteMarkers()) {
                ids.add(ObjectIdentifier.builder().key(dm.key()).versionId(dm.versionId()).build());
            }
            if (!ids.isEmpty()) {
                s3.deleteObjects(r -> r.bucket(b).delete(
                        Delete.builder().objects(ids).quiet(true).build()));
            }
        } catch (S3Exception ignored) {
            // versioning may not be enabled; ignore
        }
    }

    // -------------------------------------------------------------------------
    // One-time setup
    // -------------------------------------------------------------------------

    private static void setupTestEnvironment() throws IOException {
        final Path configDir = BASE_DIR.resolve("config");
        final Path tmpDir    = BASE_DIR.resolve("tmp");
        final Path logsDir   = BASE_DIR.resolve("logs");

        Files.createDirectories(configDir);
        Files.createDirectories(tmpDir);
        Files.createDirectories(logsDir);

        final Path keysFile = configDir.resolve("api-keys.yaml");
        final String yaml = String.format(
                "keys:%n"
                + "  - access_key: %s%n"
                + "    secret_key: %s%n"
                + "    identity: conformance-test-user%n"
                + "    enabled: true%n",
                ACCESS_KEY, SECRET_KEY);
        Files.writeString(keysFile, yaml, StandardCharsets.UTF_8);

        // PosixHygieneCheck requires the api-keys file to be mode 0600.
        try {
            final Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(keysFile, perms);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem (e.g. macOS APFS with specific mounts) — skip
        }
    }
}
