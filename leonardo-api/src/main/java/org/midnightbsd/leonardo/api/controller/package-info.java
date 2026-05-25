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
 * Micronaut HTTP controllers implementing the S3 REST API surface.
 *
 * <p>Endpoints are grouped by S3 operation category. Phase 1 (MVP) covers
 * bucket CRUD and object CRUD; later phases add multipart, configuration,
 * versioning, locking, notifications, etc. See {@code docs/project-plan.md} §8
 * for the phase breakdown.
 */
package org.midnightbsd.leonardo.api.controller;
