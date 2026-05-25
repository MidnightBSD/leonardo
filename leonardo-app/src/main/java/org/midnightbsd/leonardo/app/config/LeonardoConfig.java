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
package org.midnightbsd.leonardo.app.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Top-level Leonardo configuration. Maps the {@code leonardo.*} subtree of
 * {@code application.yml} (and any operator-supplied overrides) to a typed
 * object that other services consume via injection.
 *
 * <p>Field names use camelCase; Micronaut binds them to the YAML's snake_case
 * via its default property naming strategy.
 */
@ConfigurationProperties("leonardo")
@Introspected
public final class LeonardoConfig {

    private ServerSection server = new ServerSection();
    private StorageSection storage = new StorageSection();
    private LimitsSection limits = new LimitsSection();
    private AuthSection auth = new AuthSection();
    private LoggingSection logging = new LoggingSection();
    private FeaturesSection features = new FeaturesSection();

    public ServerSection getServer() { return server; }
    public void setServer(final ServerSection server) { this.server = server; }

    public StorageSection getStorage() { return storage; }
    public void setStorage(final StorageSection storage) { this.storage = storage; }

    public LimitsSection getLimits() { return limits; }
    public void setLimits(final LimitsSection limits) { this.limits = limits; }

    public AuthSection getAuth() { return auth; }
    public void setAuth(final AuthSection auth) { this.auth = auth; }

    public LoggingSection getLogging() { return logging; }
    public void setLogging(final LoggingSection logging) { this.logging = logging; }

    public FeaturesSection getFeatures() { return features; }
    public void setFeatures(final FeaturesSection features) { this.features = features; }

    // ---------------------------------------------------------------------

    @ConfigurationProperties("server")
    @Introspected
    public static final class ServerSection {
        private String region = "us-east-1";
        private String domain = "s3.local";
        private int adminPort = 9001;

        public String getRegion() { return region; }
        public void setRegion(final String region) { this.region = region; }

        public String getDomain() { return domain; }
        public void setDomain(final String domain) { this.domain = domain; }

        public int getAdminPort() { return adminPort; }
        public void setAdminPort(final int adminPort) { this.adminPort = adminPort; }
    }

    @ConfigurationProperties("storage")
    @Introspected
    public static final class StorageSection {
        private Path baseDir = Paths.get("/var/leonardo");
        private Path tmpDir = Paths.get("/var/leonardo/tmp");
        private boolean fsyncOnWrite = true;
        private long maxObjectSize = 5_497_558_138_880L;

        public Path getBaseDir() { return baseDir; }
        public void setBaseDir(final Path baseDir) { this.baseDir = baseDir; }

        public Path getTmpDir() { return tmpDir; }
        public void setTmpDir(final Path tmpDir) { this.tmpDir = tmpDir; }

        public boolean isFsyncOnWrite() { return fsyncOnWrite; }
        public void setFsyncOnWrite(final boolean fsyncOnWrite) { this.fsyncOnWrite = fsyncOnWrite; }

        public long getMaxObjectSize() { return maxObjectSize; }
        public void setMaxObjectSize(final long maxObjectSize) { this.maxObjectSize = maxObjectSize; }
    }

    @ConfigurationProperties("limits")
    @Introspected
    public static final class LimitsSection {
        private int maxMultipartParts = 10_000;
        private long minPartSize = 5_242_880L;
        private long maxPartSize = 5_368_709_120L;
        private int requestTimeoutSeconds = 900;

        public int getMaxMultipartParts() { return maxMultipartParts; }
        public void setMaxMultipartParts(final int maxMultipartParts) { this.maxMultipartParts = maxMultipartParts; }

        public long getMinPartSize() { return minPartSize; }
        public void setMinPartSize(final long minPartSize) { this.minPartSize = minPartSize; }

        public long getMaxPartSize() { return maxPartSize; }
        public void setMaxPartSize(final long maxPartSize) { this.maxPartSize = maxPartSize; }

        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(final int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }
    }

    @ConfigurationProperties("auth")
    @Introspected
    public static final class AuthSection {
        /** Hard cap: 7 days, matching AWS. Defaults to 3 days per the project plan. */
        public static final int PRESIGNED_URL_HARD_CAP_SECONDS = 7 * 24 * 3600;

        private String source = "yaml";
        private Path apiKeysFile = Paths.get("/var/leonardo/config/api-keys.yaml");
        private boolean allowAnonymousRead = false;
        private boolean allowSigv2 = true;
        private int presignedUrlMaxTtlSeconds = 259_200;   // 3 days

        public String getSource() { return source; }
        public void setSource(final String source) { this.source = source; }

        public Path getApiKeysFile() { return apiKeysFile; }
        public void setApiKeysFile(final Path apiKeysFile) { this.apiKeysFile = apiKeysFile; }

        public boolean isAllowAnonymousRead() { return allowAnonymousRead; }
        public void setAllowAnonymousRead(final boolean allowAnonymousRead) {
            this.allowAnonymousRead = allowAnonymousRead;
        }

        public boolean isAllowSigv2() { return allowSigv2; }
        public void setAllowSigv2(final boolean allowSigv2) { this.allowSigv2 = allowSigv2; }

        public int getPresignedUrlMaxTtlSeconds() { return presignedUrlMaxTtlSeconds; }
        public void setPresignedUrlMaxTtlSeconds(final int presignedUrlMaxTtlSeconds) {
            if (presignedUrlMaxTtlSeconds > PRESIGNED_URL_HARD_CAP_SECONDS) {
                throw new IllegalArgumentException(
                        "presigned_url_max_ttl_seconds must not exceed " + PRESIGNED_URL_HARD_CAP_SECONDS
                                + " (7 days); got " + presignedUrlMaxTtlSeconds);
            }
            this.presignedUrlMaxTtlSeconds = presignedUrlMaxTtlSeconds;
        }
    }

    @ConfigurationProperties("logging")
    @Introspected
    public static final class LoggingSection {
        private Path accessLog = Paths.get("/var/leonardo/logs/access.log");
        private Path auditLog = Paths.get("/var/leonardo/logs/audit.log");

        public Path getAccessLog() { return accessLog; }
        public void setAccessLog(final Path accessLog) { this.accessLog = accessLog; }

        public Path getAuditLog() { return auditLog; }
        public void setAuditLog(final Path auditLog) { this.auditLog = auditLog; }
    }

    @ConfigurationProperties("features")
    @Introspected
    public static final class FeaturesSection {
        private boolean versioningEnabled = true;
        private boolean multipartEnabled = true;
        private boolean presignedUrlsEnabled = true;

        public boolean isVersioningEnabled() { return versioningEnabled; }
        public void setVersioningEnabled(final boolean versioningEnabled) {
            this.versioningEnabled = versioningEnabled;
        }

        public boolean isMultipartEnabled() { return multipartEnabled; }
        public void setMultipartEnabled(final boolean multipartEnabled) {
            this.multipartEnabled = multipartEnabled;
        }

        public boolean isPresignedUrlsEnabled() { return presignedUrlsEnabled; }
        public void setPresignedUrlsEnabled(final boolean presignedUrlsEnabled) {
            this.presignedUrlsEnabled = presignedUrlsEnabled;
        }
    }
}
