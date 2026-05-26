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
 * Shared YAML utilities: a pre-configured Jackson {@link com.fasterxml.jackson.databind.ObjectMapper}
 * factory for reading and writing Leonardo's YAML metadata files.
 *
 * <p>This module is intentionally free of Micronaut so it can be used in
 * any context without an application context overhead.
 */
package org.midnightbsd.leonardo.yaml;
