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
package org.midnightbsd.leonardo.core.bucket;

import org.junit.jupiter.api.Test;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata.AclConfig;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata.CallerObjectIdsMode;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata.FsyncMode;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata.ObjectLockConfig;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata.VersioningConfig;
import org.midnightbsd.leonardo.core.bucket.BucketMetadata.VersioningStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class BucketMetadataTest {

    @Test
    void immutableAndObjectLockTogetherRejected() {
        final BucketMetadata bucket = baseBuilder()
                .immutable(true)
                .objectLock(new ObjectLockConfig(true, null, null, null))
                .build();

        assertThatThrownBy(bucket::validateInvariants)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void immutableAndVersioningEnabledRejected() {
        final BucketMetadata bucket = baseBuilder()
                .immutable(true)
                .versioning(new VersioningConfig(VersioningStatus.ENABLED, false))
                .build();

        assertThatThrownBy(bucket::validateInvariants)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versioning");
    }

    @Test
    void immutableAndVersioningSuspendedIsFine() {
        final BucketMetadata bucket = baseBuilder()
                .immutable(true)
                .versioning(new VersioningConfig(VersioningStatus.SUSPENDED, false))
                .build();

        assertThatNoException().isThrownBy(bucket::validateInvariants);
    }

    @Test
    void normalBucketPassesValidation() {
        final BucketMetadata bucket = baseBuilder().build();
        assertThatNoException().isThrownBy(bucket::validateInvariants);
    }

    // -- Small builder so tests stay readable ---------------------------------

    private static Builder baseBuilder() {
        return new Builder();
    }

    private static final class Builder {
        private boolean immutable = false;
        private VersioningConfig versioning = new VersioningConfig(VersioningStatus.DISABLED, false);
        private ObjectLockConfig objectLock = new ObjectLockConfig(false, null, null, null);

        Builder immutable(final boolean v) { this.immutable = v; return this; }
        Builder versioning(final VersioningConfig v) { this.versioning = v; return this; }
        Builder objectLock(final ObjectLockConfig v) { this.objectLock = v; return this; }

        BucketMetadata build() {
            return new BucketMetadata(
                    "test-bucket",
                    Instant.parse("2026-05-25T00:00:00Z"),
                    "alice",
                    "us-east-1",
                    versioning,
                    new AclConfig("private", List.of()),
                    Map.of(),
                    null,
                    null,
                    null,
                    null,
                    objectLock,
                    null,
                    CallerObjectIdsMode.ALLOWED,
                    FsyncMode.INHERIT,
                    null,
                    immutable,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }
}
