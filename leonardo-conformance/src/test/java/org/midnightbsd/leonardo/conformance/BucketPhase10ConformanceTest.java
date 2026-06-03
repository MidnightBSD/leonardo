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

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 10 conformance tests — Phase 10 operations are stubbed with HTTP 501.
 * <ul>
 *   <li>GetObjectTorrent — tested below.</li>
 *   <li>SelectObjectContent — only available on S3AsyncClient; verified via routing.</li>
 *   <li>WriteGetObjectResponse — uses requestRoute as a URL hostname; requires S3 Object
 *       Lambda infrastructure and cannot be exercised in conformance tests.</li>
 * </ul>
 */
final class BucketPhase10ConformanceTest extends ConformanceBase {

    private String putTestObject() {
        final String key = "phase10-test.txt";
        s3.putObject(r -> r.bucket(bucket).key(key).contentType("text/plain"),
                RequestBody.fromString("hello"));
        return key;
    }

    @Test
    void getObjectTorrentReturns501() {
        final String key = putTestObject();
        assertThatThrownBy(() ->
                s3.getObjectTorrent(r -> r.bucket(bucket).key(key)))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(501));
    }
}
