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
 * Bucket domain model: metadata records, validation, and the services that
 * operate on buckets (CRUD, ACL evaluation, lifecycle sweeping, etc.).
 *
 * <p>Schema follows project plan §4 (bucket.yaml) and §9 (bucket options).
 */
package org.midnightbsd.leonardo.core.bucket;
