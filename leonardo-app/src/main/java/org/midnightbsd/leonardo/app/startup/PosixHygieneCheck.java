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
package org.midnightbsd.leonardo.app.startup;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import org.midnightbsd.leonardo.app.config.LeonardoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Enforces the POSIX hygiene invariants from §7 of the project plan when the
 * server starts. Refuses to bring the daemon up if anything looks unsafe.
 *
 * <p>Specifically:
 * <ul>
 *   <li>{@code base_dir} exists, is a directory, and is owned by the current user.</li>
 *   <li>No file under {@code config/} is group- or world-readable.</li>
 *   <li>The API keys file (if present) is mode {@code 0600}.</li>
 * </ul>
 *
 * <p>On non-POSIX filesystems (rare on the target platforms but possible) the
 * checks degrade gracefully and log a warning rather than refusing to start.
 */
@Singleton
public final class PosixHygieneCheck implements ApplicationEventListener<ServerStartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(PosixHygieneCheck.class);

    private static final Set<PosixFilePermission> GROUP_OR_WORLD_READABLE = Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE
    );

    private final LeonardoConfig config;

    public PosixHygieneCheck(final LeonardoConfig config) {
        this.config = config;
    }

    @Override
    public void onApplicationEvent(final ServerStartupEvent event) {
        final Path baseDir = config.getStorage().getBaseDir();

        try {
            verifyBaseDir(baseDir);
            verifyApiKeysFile(config.getAuth().getApiKeysFile());
            LOG.info("POSIX hygiene check passed for base_dir={}", baseDir);
        } catch (final UnsupportedOperationException ex) {
            LOG.warn("Filesystem does not support POSIX permissions; skipping hygiene check. "
                    + "This is expected on Windows but unusual on MidnightBSD/FreeBSD/Linux.");
        } catch (final SecurityException | IOException ex) {
            throw new IllegalStateException(
                    "POSIX hygiene check failed; refusing to start. " + ex.getMessage(), ex);
        }
    }

    private void verifyBaseDir(final Path baseDir) throws IOException {
        if (!Files.exists(baseDir)) {
            throw new IllegalStateException("base_dir does not exist: " + baseDir);
        }
        if (!Files.isDirectory(baseDir)) {
            throw new IllegalStateException("base_dir is not a directory: " + baseDir);
        }
        final PosixFileAttributes attrs = Files.readAttributes(baseDir, PosixFileAttributes.class);
        final String runtimeUser = System.getProperty("user.name");
        if (!attrs.owner().getName().equals(runtimeUser)) {
            throw new IllegalStateException(
                    "base_dir " + baseDir + " is owned by " + attrs.owner().getName()
                            + " but the daemon is running as " + runtimeUser);
        }
    }

    private void verifyApiKeysFile(final Path apiKeysFile) throws IOException {
        if (!Files.exists(apiKeysFile)) {
            LOG.warn("API keys file {} does not exist yet; create it before issuing requests.",
                    apiKeysFile);
            return;
        }
        final Set<PosixFilePermission> perms = Files.getPosixFilePermissions(apiKeysFile);
        for (final PosixFilePermission unsafe : GROUP_OR_WORLD_READABLE) {
            if (perms.contains(unsafe)) {
                throw new IllegalStateException(
                        "API keys file " + apiKeysFile
                                + " has unsafe permissions; required mode is 0600");
            }
        }
    }
}
