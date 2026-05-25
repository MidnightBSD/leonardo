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
 * Striped read/write locks for per-key concurrency control. Locks are
 * short-lived — held across metadata read/write only, never across payload
 * streaming (project plan §5).
 */
package org.midnightbsd.leonardo.storage.lock;
