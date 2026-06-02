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

/**
 * S3 API conformance tests.  Each test class extends {@link
 * org.midnightbsd.leonardo.conformance.ConformanceBase}, which boots the full
 * Leonardo server on port 19000 (loopback) and provides a pre-configured AWS
 * SDK v2 {@code S3Client}.
 *
 * <p>All tests in this package run as part of {@code ./gradlew build} via the
 * {@code leonardo-conformance} submodule's {@code test} task.
 */
@SuppressWarnings("module")
package org.midnightbsd.leonardo.conformance;
