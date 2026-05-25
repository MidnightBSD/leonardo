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
 * Application-level HTTP server filters. These run before module-level
 * filters (e.g. {@code S3AuthenticationFilter} in {@code leonardo-api})
 * and enforce cross-cutting concerns such as port-based routing.
 */
package org.midnightbsd.leonardo.app.filter;
