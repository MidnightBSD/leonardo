/*
 * Copyright 2026 The Leonardo Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.midnightbsd.leonardo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

/**
 * End-to-end checks of the production server configuration over a real socket.
 *
 * <p>Two regressions are covered:
 * <ul>
 *   <li>Object and part uploads bind {@code @Body InputStream} and must run on
 *       the blocking executor; on the Netty event loop the read throws and the
 *       connection wedges until the client times out.</li>
 *   <li>{@code micronaut.server.max-request-size} must carry the configured
 *       5 TiB ceiling; a misplaced key silently leaves Micronaut's 10 MiB default.</li>
 * </ul>
 *
 * <p>{@link TestPropertyProvider} runs before the context starts, so the
 * directory layout that {@code PosixHygieneCheck} insists on exists in time.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamingUploadTest implements TestPropertyProvider {

    private static final String ACCESS_KEY = "AKIASTREAMINGTEST0001";
    private static final String SECRET_KEY = "StreamingTestSecretKey12345678901234567";
    private static final String BUCKET = "streaming-test";
    private static final long FIVE_TIB = 5_497_558_138_880L;

    @Inject
    EmbeddedServer server;

    @Inject
    HttpServerConfiguration serverConfiguration;

    private Path baseDir;
    private S3Client s3;

    @Override
    public Map<String, String> getProperties() {
        try {
            baseDir = Files.createTempDirectory("leonardo-streaming-test");
            final Path configDir = Files.createDirectories(baseDir.resolve("config"));
            Files.createDirectories(baseDir.resolve("tmp"));
            Files.createDirectories(baseDir.resolve("logs"));
            final Path keysFile = configDir.resolve("api-keys.yaml");
            Files.writeString(keysFile, String.format(
                    "keys:%n  - access_key: %s%n    secret_key: %s%n"
                            + "    identity: streaming-test-user%n    enabled: true%n",
                    ACCESS_KEY, SECRET_KEY), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(keysFile, PosixFilePermissions.fromString("rw-------"));
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return Map.of(
                "micronaut.server.netty.listeners.main.host", "127.0.0.1",
                "micronaut.server.netty.listeners.main.port", "0",
                "micronaut.server.netty.listeners.admin.host", "127.0.0.1",
                "micronaut.server.netty.listeners.admin.port", "0",
                "leonardo.storage.base_dir", baseDir.toString(),
                "leonardo.storage.tmp_dir", baseDir.resolve("tmp").toString(),
                "leonardo.storage.fsync_on_write", "false",
                "leonardo.auth.api_keys_file", baseDir.resolve("config/api-keys.yaml").toString(),
                "leonardo.logging.access_log", baseDir.resolve("logs/access.log").toString(),
                "leonardo.logging.audit_log", baseDir.resolve("logs/audit.log").toString());
    }

    @BeforeEach
    void createClientAndBucket() {
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getPort()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .socketTimeout(Duration.ofSeconds(20)))
                .overrideConfiguration(c -> c.retryStrategy(b -> b.maxAttempts(1)))
                .build();
        s3.createBucket(r -> r.bucket(BUCKET));
    }

    @AfterEach
    void deleteBucketAndClient() {
        s3.listObjectsV2(r -> r.bucket(BUCKET)).contents()
                .forEach(o -> s3.deleteObject(r -> r.bucket(BUCKET).key(o.key())));
        s3.deleteBucket(r -> r.bucket(BUCKET));
        s3.close();
    }

    @Test
    void configuredMaxRequestSizeIsApplied() {
        assertThat(serverConfiguration.getMaxRequestSize()).isEqualTo(FIVE_TIB);
    }

    @Test
    void putObjectStreamsBodyLargerThanOneNettyChunk() {
        final byte[] data = new byte[1024 * 1024];
        Arrays.fill(data, (byte) 0x5a);

        s3.putObject(r -> r.bucket(BUCKET).key("one-mebibyte"), RequestBody.fromBytes(data));

        final ResponseBytes<GetObjectResponse> got =
                s3.getObjectAsBytes(r -> r.bucket(BUCKET).key("one-mebibyte"));
        assertThat(got.asByteArray()).isEqualTo(data);
        // Streamed responses must still carry Content-Length and Content-Type.
        assertThat(got.response().contentLength()).isEqualTo((long) data.length);
        assertThat(got.response().contentType()).isEqualTo("application/octet-stream");

        final ResponseBytes<GetObjectResponse> range = s3.getObjectAsBytes(
                r -> r.bucket(BUCKET).key("one-mebibyte").range("bytes=10-73"));
        assertThat(range.asByteArray()).hasSize(64);
        assertThat(range.response().contentLength()).isEqualTo(64L);
    }

    @Test
    void bodylessPutRequestsAreAccepted() {
        s3.putObject(r -> r.bucket(BUCKET).key("source"), RequestBody.fromString("payload"));

        // CopyObject and canned-ACL PutObjectAcl send no request body at all.
        s3.copyObject(r -> r.sourceBucket(BUCKET).sourceKey("source")
                .destinationBucket(BUCKET).destinationKey("copy"));
        assertThat(s3.getObjectAsBytes(r -> r.bucket(BUCKET).key("copy")).asUtf8String())
                .isEqualTo("payload");

        s3.putObjectAcl(r -> r.bucket(BUCKET).key("source").acl(ObjectCannedACL.PRIVATE));

        // UploadPartCopy likewise has no body.
        final String uploadId = s3.createMultipartUpload(r -> r.bucket(BUCKET).key("copied-part")).uploadId();
        final String etag = s3.uploadPartCopy(r -> r.sourceBucket(BUCKET).sourceKey("source")
                .destinationBucket(BUCKET).destinationKey("copied-part")
                .uploadId(uploadId).partNumber(1)).copyPartResult().eTag();
        assertThat(etag).isNotBlank();
        s3.abortMultipartUpload(r -> r.bucket(BUCKET).key("copied-part").uploadId(uploadId));
    }

    @Test
    void putObjectAcceptsBodyLargerThanMicronautDefaultLimit() {
        final byte[] data = new byte[12 * 1024 * 1024];
        Arrays.fill(data, (byte) 0x7e);

        s3.putObject(r -> r.bucket(BUCKET).key("twelve-mebibytes"), RequestBody.fromBytes(data));

        final long size = s3.headObject(r -> r.bucket(BUCKET).key("twelve-mebibytes")).contentLength();
        assertThat(size).isEqualTo(data.length);
    }

    @Test
    void aggregatedRouteRejectsBodyLargerThanInMemoryLimit() {
        // PUT /{bucket}?tagging binds @Body Optional<String>, so Micronaut buffers
        // the whole body; the buffer cap must stop that well below max-request-size.
        final String oneMebibyte = "v".repeat(1024 * 1024);
        final List<Tag> tags = IntStream.range(0, 11)
                .mapToObj(i -> Tag.builder().key("k" + i).value(oneMebibyte).build())
                .toList();

        assertThatThrownBy(() -> s3.putBucketTagging(r -> r.bucket(BUCKET)
                        .tagging(Tagging.builder().tagSet(tags).build())))
                .isInstanceOf(S3Exception.class)
                .extracting(ex -> ((S3Exception) ex).statusCode())
                .isEqualTo(413);
    }

    @Test
    void uploadPartStreamsBody() {
        final byte[] data = new byte[256 * 1024];
        Arrays.fill(data, (byte) 0x42);

        final String uploadId = s3.createMultipartUpload(r -> r.bucket(BUCKET).key("part-object")).uploadId();
        final String etag = s3.uploadPart(
                r -> r.bucket(BUCKET).key("part-object").uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(data)).eTag();
        assertThat(etag).isNotBlank();
        s3.abortMultipartUpload(r -> r.bucket(BUCKET).key("part-object").uploadId(uploadId));
    }
}
